// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.qiyeweixin.weixin_android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * 本地模型下载 Worker：ModelScope 直链多文件顺序下载，支持断点续传。
 *
 *  - 断点续传：每个文件先写 {name}.part，已存在 .part 时带 `Range: bytes={已下}-`
 *    续传（ModelScope 已实测支持 206）；全部字节到位后 rename 成正式文件。
 *  - 进度：每 512KB 写一次 SharedPreferences（LocalModelStore.downloadedBytes）
 *    并刷新前台通知进度条；UI 侧轮询 prefs 即可。
 *  - 取消：cancelUniqueWork 后本 worker 在下一读循环检测到 stopped，保留 .part
 *    退出，状态归 none；再次下载自动从 .part 续传。
 *  - 杀进程恢复：.part 与已下载字节数都在磁盘/prefs 上，UI 检测到
 *    「state=downloading 但队列无活跃任务」时重新 enqueue 即无缝继续。
 *  - 2.47 GB 大文件：以 dataSync 前台任务运行（系统对长下载有保障），
 *    校验按字节数比对（预期大小内置自 Range 探针实测）。
 */
class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ModelDownloadWorker"
        private const val KEY_MODEL_ID = "model_id"
        private const val CHANNEL_ID = "model_download"
        private const val NOTIFICATION_ID_BASE = 4200
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_FLUSH_BYTES = 512 * 1024L

        private const val UNIQUE_PREFIX = "model_download_"

        private val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        /** 启动（或恢复）某模型下载；已在跑时不重复入队。 */
        fun enqueue(context: Context, modelId: String) {
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(Data.Builder().putString(KEY_MODEL_ID, modelId).build())
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_PREFIX + modelId, ExistingWorkPolicy.KEEP, request
            )
        }

        fun cancel(context: Context, modelId: String) {
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(UNIQUE_PREFIX + modelId)
        }

        /** 该模型的下载任务当前是否处于排队/运行中。 */
        fun isActive(context: Context, modelId: String): Boolean {
            val infos = WorkManager.getInstance(context.applicationContext)
                .getWorkInfosForUniqueWork(UNIQUE_PREFIX + modelId).get()
            return infos.any { !it.state.isFinished }
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getString(KEY_MODEL_ID)
        val model = modelId?.let { LocalModelStore.model(it) }
        if (model == null) {
            Log.w(TAG, "未知模型 id: $modelId")
            return@withContext Result.failure()
        }
        val appContext = applicationContext
        val dir = LocalModelStore.modelDir(appContext, model.id)
        dir.mkdirs()

        LocalModelStore.setState(appContext, model.id, LocalModelStore.STATE_DOWNLOADING)
        setForegroundAsync(createForegroundInfo(model, 0, ""))

        try {
            // 已完成文件的累计字节（进度基数）
            var completedBytes = model.files
                .filter { File(dir, it.name).let { f -> f.exists() && f.length() == it.size } }
                .sumOf { it.size }

            for (file in model.files) {
                val target = File(dir, file.name)
                val part = File(dir, file.name + ".part")

                // 已完成且大小匹配：跳过（重复入队/杀进程恢复时幂等）
                if (target.exists() && target.length() == file.size) continue
                // 目标存在但大小不符：损坏，重来
                if (target.exists()) target.delete()

                downloadFile(file, part, target, model, completedBytes)
                completedBytes += file.size
                LocalModelStore.setDownloaded(appContext, model.id, completedBytes)
            }

            // 最终校验：全部文件存在且字节数等于预期
            val bad = model.files.filter { f ->
                val t = File(dir, f.name)
                !t.exists() || t.length() != f.size
            }
            if (bad.isEmpty()) {
                LocalModelStore.setState(appContext, model.id, LocalModelStore.STATE_READY)
                LocalModelStore.setDownloaded(appContext, model.id, model.totalBytes)
                Log.i(TAG, "模型 ${model.id} 下载完成并校验通过")
                Result.success()
            } else {
                val msg = "校验失败：" + bad.joinToString(",") { it.name }
                LocalModelStore.setState(appContext, model.id, LocalModelStore.STATE_ERROR, msg)
                Log.w(TAG, "模型 ${model.id} $msg")
                Result.failure()
            }
        } catch (e: CancelledByUserException) {
            // 用户取消/进程被杀：保留 .part，状态归 none（下次下载自动续传）
            LocalModelStore.setState(appContext, model.id, LocalModelStore.STATE_NONE)
            Log.i(TAG, "模型 ${model.id} 下载已取消，已下载部分保留")
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "模型 ${model.id} 下载失败", e)
            LocalModelStore.setState(
                appContext, model.id, LocalModelStore.STATE_ERROR,
                e.message ?: "网络错误"
            )
            // 网络类错误交给 WorkManager 退避重试一次；仍失败则停在 error 状态
            if (runAttemptCount < 1) Result.retry() else Result.failure()
        }
    }

    private class CancelledByUserException : Exception("cancelled")

    /** 单文件下载：.part 续传 + 周期进度上报；stopped 时抛 CancelledByUserException。 */
    private suspend fun downloadFile(
        file: LocalModelStore.ModelFile,
        part: File,
        target: File,
        model: LocalModelStore.LocalModel,
        completedBytes: Long
    ) {
        val appContext = applicationContext
        val resumed = if (part.exists()) part.length() else 0L
        if (resumed >= file.size) {
            // .part 已完整（上次 rename 前被杀）：直接转正
            if (part.renameTo(target)) return
            part.delete()
        }

        val requestBuilder = Request.Builder().url(file.url).get()
        if (resumed > 0) {
            requestBuilder.header("Range", "bytes=$resumed-")
            Log.i(TAG, "${file.name} 断点续传，从 $resumed 字节继续")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            // 续传时服务端必须回 206；若回 200 说明不支持/不认 Range，从头下
            val append = response.code == 206 && resumed > 0
            if (!response.isSuccessful) {
                throw java.io.IOException("HTTP ${response.code} @ ${file.name}")
            }
            val body = response.body ?: throw java.io.IOException("空响应 @ ${file.name}")

            var written = if (append) resumed else 0L
            var sinceFlush = 0L
            body.byteStream().use { input ->
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        if (isStopped) throw CancelledByUserException()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        written += n
                        sinceFlush += n
                        if (sinceFlush >= PROGRESS_FLUSH_BYTES) {
                            sinceFlush = 0
                            val done = completedBytes + written
                            LocalModelStore.setDownloaded(appContext, model.id, done)
                            val pct = ((done * 100) / model.totalBytes).toInt().coerceIn(0, 100)
                            setForegroundAsync(createForegroundInfo(model, pct, file.name))
                        }
                    }
                    output.fd.sync()
                }
            }
            if (written != file.size) {
                throw java.io.IOException(
                    "${file.name} 字节数不符：期望 ${file.size}，实收 $written"
                )
            }
        }

        if (!part.renameTo(target)) {
            throw java.io.IOException("${file.name} 落盘改名失败")
        }
    }

    /** 前台任务通知（进度条）；Android 14 起需显式 dataSync 类型。 */
    private fun createForegroundInfo(
        model: LocalModelStore.LocalModel,
        percent: Int,
        currentFile: String
    ): ForegroundInfo {
        val manager = applicationContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "模型下载", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val text = if (currentFile.isEmpty()) "准备下载…"
        else "$currentFile  $percent%"
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("正在下载 ${model.displayName}")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, percent, percent == 0)
            .setOngoing(true)
            .build()
        val id = NOTIFICATION_ID_BASE + model.id.hashCode() % 100
        return if (Build.VERSION.SDK_INT >= 34) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }
}
