package com.ai.companion

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ai.companion.ai.UserPreferences

class RulesActivity : AppCompatActivity() {

    private lateinit var userPreferences: UserPreferences

    private lateinit var etFocusKeywords: EditText
    private lateinit var etIgnoreKeywords: EditText
    private lateinit var etUserName: EditText
    private lateinit var switchAutoRespondQuestions: Switch
    private lateinit var switchAutoRespondFacts: Switch
    private lateinit var seekBarThreshold: SeekBar
    private lateinit var tvThresholdValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        userPreferences = UserPreferences(this)

        val scrollView = ScrollView(this).apply {
            layoutParams = ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.MATCH_PARENT
            )
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(24), dpToPx(24), dpToPx(24), dpToPx(24))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Title
        val tvTitle = TextView(this).apply {
            text = "智能规则设置"
            textSize = 24f
            setTextColor(Color.parseColor("#6200EE"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(24)
            }
        }
        mainLayout.addView(tvTitle)

        // Section 1: 关注领域
        val tvSection1Title = createSectionTitle("关注领域")
        mainLayout.addView(tvSection1Title)

        val tvFocusHint = createDescriptionText("设置你感兴趣的关键词，AI会优先关注包含这些词的对话")
        mainLayout.addView(tvFocusHint)

        etFocusKeywords = EditText(this).apply {
            hint = "例如: AI,编程,股票,天气,新闻"
            minLines = 3
            maxLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(userPreferences.getFocusKeywords().joinToString(","))
            layoutParams = createEditTextParams()
        }
        mainLayout.addView(etFocusKeywords)

        addSectionSpacing(mainLayout)

        // Section 2: 忽略内容
        val tvSection2Title = createSectionTitle("忽略内容")
        mainLayout.addView(tvSection2Title)

        val tvIgnoreHint = createDescriptionText("设置要忽略的关键词，包含这些词的对话不会被分析")
        mainLayout.addView(tvIgnoreHint)

        etIgnoreKeywords = EditText(this).apply {
            hint = "例如: 嗯,啊,哦,哈哈"
            minLines = 2
            maxLines = 2
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(userPreferences.getIgnoreKeywords().joinToString(","))
            layoutParams = createEditTextParams()
        }
        mainLayout.addView(etIgnoreKeywords)

        addSectionSpacing(mainLayout)

        // Section 3: 个人信息
        val tvSection3Title = createSectionTitle("个人信息")
        mainLayout.addView(tvSection3Title)

        val tvNameLabel = createDescriptionText("你的名字")
        mainLayout.addView(tvNameLabel)

        etUserName = EditText(this).apply {
            hint = "输入你的名字，AI会识别对话中提到你的内容"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(userPreferences.getUserName())
            layoutParams = createEditTextParams()
        }
        mainLayout.addView(etUserName)

        addSectionSpacing(mainLayout)

        // Section 4: 响应设置
        val tvSection4Title = createSectionTitle("响应设置")
        mainLayout.addView(tvSection4Title)

        switchAutoRespondQuestions = Switch(this).apply {
            text = "自动回答问题"
            isChecked = userPreferences.isAutoRespondQuestions()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        mainLayout.addView(switchAutoRespondQuestions)

        switchAutoRespondFacts = Switch(this).apply {
            text = "自动解说事实"
            isChecked = userPreferences.isAutoRespondFacts()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        mainLayout.addView(switchAutoRespondFacts)

        val tvThresholdLabel = createDescriptionText("重要度阈值")
        mainLayout.addView(tvThresholdLabel)

        tvThresholdValue = TextView(this).apply {
            text = "${userPreferences.getImportanceThreshold()}"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(8)
            }
        }
        mainLayout.addView(tvThresholdValue)

        seekBarThreshold = SeekBar(this).apply {
            max = 100
            progress = userPreferences.getImportanceThreshold()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    tvThresholdValue.text = "$progress"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        mainLayout.addView(seekBarThreshold)

        addSectionSpacing(mainLayout)

        // Save button
        val btnSave = Button(this).apply {
            text = "保存规则"
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(16)
                bottomMargin = dpToPx(32)
            }
            setOnClickListener {
                saveRules()
            }
        }
        mainLayout.addView(btnSave)

        scrollView.addView(mainLayout)
        setContentView(scrollView)
    }

    private fun saveRules() {
        val focusKeywords = etFocusKeywords.text.toString()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val ignoreKeywords = etIgnoreKeywords.text.toString()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val userName = etUserName.text.toString().trim()

        userPreferences.setFocusKeywords(focusKeywords)
        userPreferences.setIgnoreKeywords(ignoreKeywords)
        userPreferences.setUserName(userName)
        userPreferences.setAutoRespondQuestions(switchAutoRespondQuestions.isChecked)
        userPreferences.setAutoRespondFacts(switchAutoRespondFacts.isChecked)
        userPreferences.setImportanceThreshold(seekBarThreshold.progress)

        Toast.makeText(this, "规则已保存", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun createSectionTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 18f
            setTextColor(Color.parseColor("#212121"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(8)
            }
        }
    }

    private fun createDescriptionText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.parseColor("#757575"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(8)
            }
        }
    }

    private fun createEditTextParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dpToPx(8)
        }
    }

    private fun addSectionSpacing(layout: LinearLayout) {
        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(16)
            )
        }
        layout.addView(spacer)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
