package com.tomilov.stylishsat.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent

/** Preview is returned before any outbound action. Copy/share are explicit user actions only. */
object ChatGptHandoff {
    fun preview(request: TutorRequest): String = buildString {
        append(TutorPromptBuilder.system(request.language).removeSuffix("Stay concise (at most 512 tokens).").trimEnd())
        append(" Provide a detailed explanation, specific examples and a short next-practice checklist.")
        append("\n\nThis is practice feedback requested by the learner.\n\n")
        append(TutorPromptBuilder.core(request))
        request.excerpts.forEach { append("\n\nSOURCE ${it.sourceId}:\n${it.text}") }
    }

    fun copy(context: Context, text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText("StylishSAT · ChatGPT prompt", text),
        )
    }

    fun share(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, "StylishSAT practice feedback")
        }
        context.startActivity(Intent.createChooser(intent, "Share your practice prompt").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
