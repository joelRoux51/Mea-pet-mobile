package com.meapet.mobile.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.meapet.mobile.R

/**
 * 底部浮动输入栏组件。
 *
 * 从 ChatScreen 中提取的独立组件，支持 loading 状态和发送回调。
 *
 * @param inputText 当前输入文本
 * @param onInputChange 输入变更回调
 * @param onSend 发送回调
 * @param isLoading 是否正在等待响应
 * @param placeholder 占位文本
 * @param enabled 是否可交互
 * @param glassmorphism 是否使用沉浸模式半透明毛玻璃外观
 * @param modifier Modifier
 */
@Composable
fun ChatInputBar(
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    isLoading: Boolean = false,
    placeholder: String = stringResource(R.string.chat_input_placeholder),
    enabled: Boolean = true,
    glassmorphism: Boolean = false,
    modifier: Modifier = Modifier
) {
    val containerColor = if (glassmorphism) {
        Color(0xFF141926).copy(alpha = 0.72f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
    }
    val textColor = if (glassmorphism) {
        Color.White
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val borderStroke = if (glassmorphism) {
        BorderStroke(1.dp, Color.White.copy(alpha = 0.20f))
    } else {
        null
    }
    val outerPadding = if (glassmorphism) {
        Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
    } else {
        Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    }
    val fieldVerticalPadding = if (glassmorphism) 14.dp else 20.dp

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(outerPadding),
        shape = RoundedCornerShape(28.dp),
        tonalElevation = if (glassmorphism) 0.dp else 8.dp,
        // 保持 0：投影会渗过半透明填充，在浅色模式下形成一条亮带
        shadowElevation = 0.dp,
        border = borderStroke,
        color = containerColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 用 BasicTextField 而非 M3 TextField：后者的装饰盒会画自己的容器和指示线，
            // 容器色按 focused/unfocused/disabled/error 分档，漏一档就在半透明药丸上露底。
            BasicTextField(
                value = inputText,
                onValueChange = { if (!isLoading) onInputChange(it) },
                modifier = Modifier.weight(1f),
                enabled = enabled,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = if (enabled) textColor else textColor.copy(alpha = 0.5f)
                ),
                cursorBrush = SolidColor(textColor),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (!isLoading) onSend() }),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier.padding(vertical = fieldVerticalPadding),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (inputText.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = textColor.copy(alpha = 0.62f)
                            )
                        }
                        innerTextField()
                    }
                }
            )

            Spacer(modifier = Modifier.size(8.dp))

            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(40.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                IconButton(
                    onClick = onSend,
                    enabled = enabled && inputText.isNotBlank(),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                        disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.4f)
                    )
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_send),
                        contentDescription = stringResource(R.string.action_send),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
