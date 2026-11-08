package com.mazwas.coverlauncher.feature.launcher.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mazwas.coverlauncher.R
import com.mazwas.coverlauncher.core.designsystem.Label
import com.mazwas.coverlauncher.core.designsystem.status
import com.mazwas.coverlauncher.feature.launcher.domain.IconStyle
import com.mazwas.coverlauncher.feature.launcher.domain.LaunchableApp

/** All apps A–Z with search. Pull down at the top (or Back) to close. */
@Composable
fun AppDrawer(state: LauncherState, onAction: (LauncherAction) -> Unit, modifier: Modifier = Modifier) {
  val cutout = WindowInsets.displayCutout.asPaddingValues().calculateBottomPadding()
  val pullToClose = rememberPullPastEnd(down = true) { onAction(LauncherAction.Close) }
  Column(
    modifier
      .fillMaxSize()
      .background(Color.Black)
      .imePadding()
      .verticalSwipe(onDown = { onAction(LauncherAction.Close) })
      .padding(top = 10.dp),
  ) {
    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
      SearchField(
        state.query,
        onChange = { onAction(LauncherAction.Search(it)) },
        onGo = { onAction(LauncherAction.LaunchFirst) },
        modifier = Modifier.weight(1f),
      )
      Spacer(Modifier.width(12.dp))
      Label("${state.appCount} apps", small = true)
    }
    if (state.apps.isEmpty()) {
      Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Label("no match", color = MaterialTheme.status.disabled)
      }
    } else {
      LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        modifier = Modifier.weight(1f).nestedScroll(pullToClose),
        contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 8.dp, bottom = cutout + 8.dp),
      ) {
        items(state.apps, key = { it.key }) { app ->
          DrawerItem(app, pinned = app in state.dock, style = state.iconStyle, onAction = onAction)
        }
      }
    }
  }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, onGo: () -> Unit, modifier: Modifier = Modifier) {
  val line = MaterialTheme.colorScheme.outline
  BasicTextField(
    value = query,
    onValueChange = onChange,
    singleLine = true,
    textStyle = MaterialTheme.typography.titleSmall.copy(color = MaterialTheme.colorScheme.onSurface),
    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
    keyboardActions = KeyboardActions(onGo = { onGo() }),
    modifier = modifier,
    decorationBox = { field ->
      Row(
        Modifier
          .drawBehind { drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
          .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          painterResource(R.drawable.ic_search), contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
          if (query.isEmpty()) Label("search", color = MaterialTheme.status.disabled)
          field()
        }
      }
    },
  )
}

@Composable
private fun DrawerItem(app: LaunchableApp, pinned: Boolean, style: IconStyle, onAction: (LauncherAction) -> Unit) {
  var menu by remember { mutableStateOf(false) }
  Box {
    Column(
      Modifier
        .fillMaxWidth()
        .pressable(onClick = { onAction(LauncherAction.Launch(app)) }, onLongClick = { menu = true })
        .padding(vertical = 6.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      AppIcon(app, style, 44.dp)
      Spacer(Modifier.height(5.dp))
      Text(
        app.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 2.dp),
      )
    }
    DropdownMenu(
      expanded = menu,
      onDismissRequest = { menu = false },
      shape = RoundedCornerShape(12.dp),
      containerColor = MaterialTheme.colorScheme.surfaceVariant,
      tonalElevation = 0.dp,
      shadowElevation = 0.dp,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
      MenuItem(R.drawable.ic_pin, if (pinned) "unpin" else "pin to dock") { menu = false; onAction(LauncherAction.TogglePin(app)) }
      MenuItem(R.drawable.ic_info, "app info") { menu = false; onAction(LauncherAction.AppInfo(app)) }
      MenuItem(R.drawable.ic_trash_2, "uninstall", MaterialTheme.colorScheme.error) {
        menu = false
        onAction(LauncherAction.Uninstall(app))
      }
    }
  }
}

@Composable
private fun MenuItem(icon: Int, text: String, color: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) =
  DropdownMenuItem(
    text = { Label(text, color = color) },
    leadingIcon = { Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(16.dp)) },
    onClick = onClick,
  )
