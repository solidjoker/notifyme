// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.qiyeweixin.weixin_android

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * 本地模型管理页：两个内置模型（S1 快筛 / S2 深分析）的下载、进度、取消、删除。
 *
 *  - 状态来源：LocalModelStore（SharedPreferences）+ ModelDownloadWorker（执行）；
 *    页面在前台时每 1s 轮询刷新一次（下载进度由 worker 周期写 prefs）。
 *  - 杀进程恢复：onResume 时发现「state=downloading 但队列里无活跃任务」
 *    （进程被杀导致 worker 消失）则自动重新入队，从 .part 断点续传。
 *  - 删除：清整个模型目录（含 .part 残留），若正在下载先取消。
 */
class ModelListActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var polling = false

    private val refresher = object : Runnable {
        override fun run() {
            refreshAll()
            if (polling) handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_list)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        bindModel(
            LocalModelStore.MODEL_S1,
            R.id.tvModelS1Status, R.id.progressModelS1,
            R.id.btnModelS1Action, R.id.btnModelS1Delete
        )
        bindModel(
            LocalModelStore.MODEL_S2,
            R.id.tvModelS2Status, R.id.progressModelS2,
            R.id.btnModelS2Action, R.id.btnModelS2Delete
        )
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        // 下载中状态但任务已消失（进程被杀）：自动恢复下载，断点续传
        LocalModelStore.MODELS.forEach { m ->
            if (LocalModelStore.state(this, m.id) == LocalModelStore.STATE_DOWNLOADING &&
                !ModelDownloadWorker.isActive(this, m.id)
            ) {
                ModelDownloadWorker.enqueue(this, m.id)
            }
        }
        polling = true
        handler.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        polling = false
        handler.removeCallbacks(refresher)
    }

    private fun bindModel(modelId: String, tvId: Int, barId: Int, actionId: Int, deleteId: Int) {
        findViewById<Button>(actionId).setOnClickListener {
            when (LocalModelStore.state(this, modelId)) {
                LocalModelStore.STATE_DOWNLOADING -> {
                    ModelDownloadWorker.cancel(this, modelId)
                    Toast.makeText(this, R.string.model_cancel_toast, Toast.LENGTH_SHORT).show()
                }
                else -> startDownload(modelId)
            }
            handler.postDelayed({ refreshAll() }, 300)
        }
        findViewById<Button>(deleteId).setOnClickListener { confirmDelete(modelId) }
    }

    private fun startDownload(modelId: String) {
        val model = LocalModelStore.model(modelId) ?: return
        // 存储空间预检：模型大小 + 100MB 余量（.part 与正式文件短时并存）
        val available = StatFs(filesDir.absolutePath).availableBytes
        if (available < model.totalBytes + 100L * 1024 * 1024) {
            Toast.makeText(
                this,
                getString(
                    R.string.model_no_space,
                    LocalModelStore.formatBytes(model.totalBytes + 100L * 1024 * 1024),
                    LocalModelStore.formatBytes(available)
                ),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        ModelDownloadWorker.enqueue(this, modelId)
        LocalModelStore.setState(this, modelId, LocalModelStore.STATE_DOWNLOADING)
    }

    private fun confirmDelete(modelId: String) {
        val model = LocalModelStore.model(modelId) ?: return
        val onDisk = LocalModelStore.onDiskBytes(this, modelId)
        AlertDialog.Builder(this)
            .setTitle(R.string.model_delete_title)
            .setMessage(
                getString(
                    R.string.model_delete_message,
                    model.displayName,
                    LocalModelStore.formatBytes(onDisk)
                )
            )
            .setPositiveButton(R.string.model_btn_delete) { _, _ ->
                ModelDownloadWorker.cancel(this, modelId)
                LocalModelStore.delete(this, modelId)
                LocalLlmEngines.releaseAll() // 已加载引擎随模型删除一并释放
                refreshAll()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshAll() {
        refreshModel(
            LocalModelStore.MODEL_S1,
            R.id.tvModelS1Status, R.id.progressModelS1,
            R.id.btnModelS1Action, R.id.btnModelS1Delete
        )
        refreshModel(
            LocalModelStore.MODEL_S2,
            R.id.tvModelS2Status, R.id.progressModelS2,
            R.id.btnModelS2Action, R.id.btnModelS2Delete
        )
    }

    private fun refreshModel(modelId: String, tvId: Int, barId: Int, actionId: Int, deleteId: Int) {
        val model = LocalModelStore.model(modelId) ?: return
        val tv = findViewById<TextView>(tvId)
        val bar = findViewById<ProgressBar>(barId)
        val actionBtn = findViewById<Button>(actionId)
        val deleteBtn = findViewById<Button>(deleteId)

        val state = LocalModelStore.state(this, modelId)
        val pct = LocalModelStore.progressPercent(this, modelId)
        val onDisk = LocalModelStore.onDiskBytes(this, modelId)

        when (state) {
            LocalModelStore.STATE_DOWNLOADING -> {
                bar.visibility = ProgressBar.VISIBLE
                bar.progress = pct
                tv.text = getString(
                    R.string.model_status_downloading,
                    pct,
                    LocalModelStore.formatBytes(LocalModelStore.downloadedBytes(this, modelId)),
                    model.sizeLabel
                )
                tv.setTextColor(getColor(R.color.status_gray))
                actionBtn.setText(R.string.model_btn_cancel)
                actionBtn.isEnabled = true
                deleteBtn.isEnabled = false
            }
            LocalModelStore.STATE_READY -> {
                bar.visibility = ProgressBar.GONE
                tv.text = getString(R.string.model_status_ready, model.sizeLabel)
                tv.setTextColor(getColor(R.color.status_ok))
                actionBtn.setText(R.string.model_btn_ready)
                actionBtn.isEnabled = false
                deleteBtn.isEnabled = true
            }
            LocalModelStore.STATE_ERROR -> {
                bar.visibility = ProgressBar.GONE
                val err = LocalModelStore.lastError(this, modelId)
                tv.text = getString(
                    R.string.model_status_error,
                    if (onDisk > 0) getString(
                        R.string.model_status_error_resume,
                        LocalModelStore.formatBytes(onDisk)
                    ) else "",
                    err
                )
                tv.setTextColor(getColor(R.color.status_error))
                actionBtn.setText(R.string.model_btn_retry)
                actionBtn.isEnabled = true
                deleteBtn.isEnabled = onDisk > 0
            }
            else -> {
                bar.visibility = ProgressBar.GONE
                // .part 残留（取消过/失败过）给「可续传」提示
                tv.text = if (onDisk > 0) getString(
                    R.string.model_status_partial,
                    model.sizeLabel,
                    LocalModelStore.formatBytes(onDisk)
                ) else getString(R.string.model_status_none, model.sizeLabel)
                tv.setTextColor(getColor(R.color.status_gray))
                actionBtn.setText(R.string.model_btn_download)
                actionBtn.isEnabled = true
                deleteBtn.isEnabled = onDisk > 0
            }
        }
    }
}
