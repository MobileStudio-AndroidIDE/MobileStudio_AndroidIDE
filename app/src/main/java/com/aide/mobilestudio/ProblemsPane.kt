package com.aide.mobilestudio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aide.mobilestudio.ui.ide.LocalIdeColors

/** Live compiler errors shown in the Android Studio-style Problems tool window. */
data class EditorProblem(val line: Int, val message: String, val severity: String = "ERROR")

@Composable
fun ProblemsPane(
    problems: List<EditorProblem>,
    onProblemFix: (Int) -> Unit,
) {
    val ide = LocalIdeColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(ide.p.editorBg)),
    ) {
        if (problems.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No problems detected",
                    color = ide.muted,
                    fontSize = 12.sp,
                )
            }
            return
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(problems) { problem ->
                val isError = !problem.severity.equals("WARNING", ignoreCase = true)
                val accent = if (isError) ide.error else ide.warning
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onProblemFix(problem.line - 1) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(accent.copy(alpha = 0.18f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("!", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = problem.message,
                            color = ide.text,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "Line ${problem.line}",
                            color = ide.textSoft,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
    }
}
