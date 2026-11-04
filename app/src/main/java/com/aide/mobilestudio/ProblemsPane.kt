package com.aide.mobilestudio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 실시간 컴파일 오류 항목 */
data class EditorProblem(val line: Int, val message: String, val severity: String = "ERROR")

/** 에디터 하단 컴파일 오류 패널 (이미지 1 참조) */
@Composable
fun ProblemsPane(
    problems: List<EditorProblem>,
    onProblemFix: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1E))
            .border(1.dp, Color(0xFF3A3A4A))
    ) {
        // 헤더
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .background(Color(0xFF2A2A2E))
        ) {
            Text(
                text = "⚠ ${problems.size} problem(s)",
                color = Color(0xFFFF5555),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "Build",
                color = Color(0xFF888888),
                fontSize = 11.sp,
            )
        }
        Divider(color = Color(0xFF3A3A4A), modifier = Modifier.fillMaxWidth())
        // 오류 목록
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            items(problems) { problem ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { onProblemFix(problem.line - 1) }
                        .background(Color.Transparent)
                ) {
                    Text(
                        text = "🔴",
                        color = Color(0xFFFF5555),
                        fontSize = 12.sp,
                        modifier = Modifier.size(16.dp)
                    )
                    Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(
                            text = problem.message,
                            color = Color(0xFFE0E0E0),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "Line ${problem.line}",
                            color = Color(0xFF888888),
                            fontSize = 11.sp,
                        )
                    }
                }
                Divider(color = Color(0xFF2A2A2E), modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
