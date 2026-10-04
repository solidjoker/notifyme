// Copyright (c) 2026 solidjoker
// SPDX-License-Identifier: MIT

package com.qiyeweixin.weixin_android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/**
 * 会话级自定义提示词编辑页。
 *
 * 数据源：PromptStore（prefs prompt_config，key=会话名，空白=清除=用全局默认）。
 * 内置 3 个模板：点击直接填入编辑框（整体替换），占位符 {我}/{重要的人}/{关系} 由用户手改，
 * 刻意不做变量表单——让用户保留对提示词全文的控制权。
 */
class PromptEditActivity : Activity() {

    companion object {
        const val EXTRA_CONVERSATION = "extra_conversation"

        fun start(context: Context, conversation: String) {
            context.startActivity(
                Intent(context, PromptEditActivity::class.java)
                    .putExtra(EXTRA_CONVERSATION, conversation)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_prompt_edit)

        val conversation = intent.getStringExtra(EXTRA_CONVERSATION).orEmpty()
        if (conversation.isEmpty()) {
            finish()
            return
        }

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tvConversationName).text = conversation

        val etPrompt = findViewById<EditText>(R.id.etPrompt)
        etPrompt.setText(PromptStore.getPrompt(this, conversation))

        // 三个内置模板：点击直接填入（整体替换编辑框内容）
        findViewById<Button>(R.id.btnTemplate1).setOnClickListener {
            etPrompt.setText(getString(R.string.prompt_template_1))
        }
        findViewById<Button>(R.id.btnTemplate2).setOnClickListener {
            etPrompt.setText(getString(R.string.prompt_template_2))
        }
        findViewById<Button>(R.id.btnTemplate3).setOnClickListener {
            etPrompt.setText(getString(R.string.prompt_template_3))
        }

        findViewById<Button>(R.id.btnSavePrompt).setOnClickListener {
            // setPrompt 内部对空白文本做清除处理
            PromptStore.setPrompt(this, conversation, etPrompt.text.toString())
            Toast.makeText(this, R.string.prompt_edit_saved, Toast.LENGTH_SHORT).show()
            finish()
        }

        findViewById<Button>(R.id.btnClearPrompt).setOnClickListener {
            PromptStore.clearPrompt(this, conversation)
            Toast.makeText(this, R.string.prompt_edit_cleared, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
