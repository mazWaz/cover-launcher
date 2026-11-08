package com.mazwas.coverlauncher.feature.launcher.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mazwas.coverlauncher.R
import com.mazwas.coverlauncher.core.designsystem.Label
import com.mazwas.coverlauncher.core.designsystem.NothingTheme
import com.mazwas.coverlauncher.core.designsystem.cutoutSides
import com.mazwas.coverlauncher.core.designsystem.status
import com.mazwas.coverlauncher.feature.launcher.domain.LauncherNotification

/**
 * Answering [note] through its Reply button without opening the app: typed or dictated, sent with the keyboard's Send
 * or the arrow. The box sits at the top so the keyboard below never covers it (and nothing tappable lands on the
 * camera). Back closes it.
 */
@Composable
fun ReplySheet(note: LauncherNotification, text: String, listening: Boolean, onAction: (LauncherAction) -> Unit) {
  val focus = remember { FocusRequester() }
  LaunchedEffect(note.key) { focus.requestFocus() }
  Column(Modifier.fillMaxSize().background(Color.Black).cutoutSides().padding(horizontal = 16.dp, vertical = 12.dp)) {
    Label(note.appLabel, small = true)
    if (note.title.isNotBlank()) Text(note.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (note.text.isNotBlank()) {
      Text(
        note.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2, overflow = TextOverflow.Ellipsis,
      )
    }
    Spacer(Modifier.height(10.dp))
    val line = MaterialTheme.colorScheme.outline
    Row(
      Modifier.drawBehind { drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) },
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
        if (text.isEmpty()) Label(if (listening) "listening…" else "reply", color = MaterialTheme.status.disabled)
        BasicTextField(
          value = text,
          onValueChange = { onAction(LauncherAction.ReplyText(it)) },
          maxLines = 3,
          textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
          cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
          keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
          keyboardActions = KeyboardActions(onSend = { onAction(LauncherAction.SendReply) }),
          modifier = Modifier.focusRequester(focus),
        )
      }
      Spacer(Modifier.width(8.dp))
      // red only while the microphone is on: a signal, like recording
      ReplyButton(R.drawable.ic_mic, if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) {
        onAction(LauncherAction.Dictate)
      }
      ReplyButton(R.drawable.ic_send, if (text.isBlank()) MaterialTheme.status.disabled else MaterialTheme.colorScheme.onSurface) {
        onAction(LauncherAction.SendReply)
      }
    }
  }
}

@Composable
private fun ReplyButton(icon: Int, tint: Color, onClick: () -> Unit) =
  Box(Modifier.size(44.dp).pressable(onClick), contentAlignment = Alignment.Center) {
    Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
  }

@Preview(widthDp = 352, heightDp = 339)
@Composable
private fun ReplyPreview() = NothingTheme {
  ReplySheet(LauncherNotification("a", "wa", "WhatsApp", "Dimas", "Nanti malam jadi?", 0, true, listOf("Reply"), reply = 0), "", false) {}
}
