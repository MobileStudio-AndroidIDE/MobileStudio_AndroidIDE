package com.aide.mobilestudio.ai.opencode.tool

import android.content.Context
import com.aide.mobilestudio.ai.opencode.skill.OcSkill
import org.json.JSONArray
import org.json.JSONObject

class TodoWriteTool : OcTool {
    override val name = "todowrite"

    override fun description(ctx: ToolContext): String =
        OcAssets.read(ctx.androidContext, "ai/prompt/tool/todowrite.txt")
            ?: "Create and maintain a structured task list for the current coding session."

    override fun parameters(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("todos", JSONObject().put("type", "array").put("description", "The updated todo list").put("items", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("content", JSONObject().put("type", "string").put("description", "Brief description of the task"))
                    put("status", JSONObject().put("type", "string").put("description", "Current status of the task: pending, in_progress, completed, cancelled"))
                    put("priority", JSONObject().put("type", "string").put("description", "Priority level of the task: high, medium, low"))
                })
                put("required", JSONArray().put("content").put("status").put("priority"))
            }))
        })
        put("required", JSONArray().put("todos"))
    }

    override fun permissionKey(): String = "todowrite"

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val todos = args.optJSONArray("todos") ?: return ToolResult("Error: todos is required", isError = true)
        val normalized = JSONArray()
        for (i in 0 until todos.length()) {
            val todo = todos.optJSONObject(i) ?: continue
            normalized.put(JSONObject().apply {
                put("content", todo.optString("content"))
                put("status", todo.optString("status", "pending"))
                put("priority", todo.optString("priority", "medium"))
            })
        }
        ctx.bridge.onTodos(normalized)
        return ToolResult("Todos have been modified successfully. Ensure that you continue to use the todo list to track your progress.")
    }
}

class TaskTool : OcTool {
    override val name = "task"

    override fun description(ctx: ToolContext): String =
        OcAssets.read(ctx.androidContext, "ai/prompt/tool/task.txt")
            ?: "Launch a new agent to handle complex, multistep tasks autonomously."

    override fun parameters(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("description", JSONObject().put("type", "string").put("description", "A short (3-5 words) description of the task"))
            put("prompt", JSONObject().put("type", "string").put("description", "The task for the agent to perform"))
            put("subagent_type", JSONObject().put("type", "string").put("description", "The type of specialized agent to use for this task"))
            put("task_id", JSONObject().put("type", "string").put("description", "Set only if you mean to resume a previous task"))
            put("command", JSONObject().put("type", "string").put("description", "The command that triggered this task"))
            put("background", JSONObject().put("type", "boolean").put("description", "Run the agent in the background. You will be notified when it completes."))
        })
        put("required", JSONArray().put("description").put("prompt").put("subagent_type"))
    }

    override fun permissionKey(): String = "task"

    override fun resourcePattern(args: JSONObject, ctx: ToolContext): String = args.optString("subagent_type", "")

    override fun riskHint(args: JSONObject, ctx: ToolContext): RiskHint = RiskHint.BUILD

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val prompt = args.optString("prompt", "")
        val subagentType = args.optString("subagent_type", "general")
        val description = args.optString("description", "Task")
        val taskId = args.optStringOrNull("task_id")
        val background = args.optBoolean("background", false)
        if (prompt.isBlank()) return ToolResult("Error: prompt is required", isError = true)
        // New work goes through the common orchestrator. The legacy path is
        // kept only for resuming an existing subagent session or an explicitly
        // requested background task; it is still depth-limited by the engine.
        if (!background && taskId == null) {
            val request = JSONObject().put(
                "tasks",
                JSONArray().put(
                    JSONObject().apply {
                        put("description", description)
                        put("prompt", prompt)
                        put("subagent_type", subagentType)
                    },
                ),
            )
            val output = ctx.bridge.delegateTasks(
                request = request,
                parentSessionId = ctx.sessionId,
                depth = ctx.depth,
                rootSessionId = ctx.rootSessionId,
                parentTaskId = ctx.orchestrationTaskId,
            )
            return ToolResult(
                output,
                isError = output.startsWith("DELEGATION FAILED") || output.startsWith("DELEGATION REJECTED"),
            )
        }
        return ToolResult(
            ctx.bridge.spawnSubagent(
                subagentType, prompt, description, taskId, ctx.sessionId, background, ctx.depth,
            ),
        )
    }
}

/**
 * Delegation entry point for every model, local or API.
 *
 * A model proposes a batch; the orchestrator validates it, chooses the actual
 * model (which may be in another provider), enforces depth/budget/permissions
 * and runs it. The model never receives another provider's key.
 */
class DelegateTool : OcTool {
    override val name = "delegate"

    override fun description(ctx: ToolContext): String =
        OcAssets.read(ctx.androidContext, "ai/prompt/tool/delegate.txt")
            ?: "Delegate independent subtasks to the best available local or API model."

    override fun parameters(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("tasks", JSONObject().apply {
                put("type", "array")
                put("description", "Independent subtasks. The orchestrator runs independent tasks in parallel.")
                put("items", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("description", JSONObject().put("type", "string").put("description", "Short task label (3-8 words)"))
                        put("prompt", JSONObject().put("type", "string").put("description", "Complete, self-contained instructions for the subagent"))
                        put("subagent_type", JSONObject().put("type", "string").put("description", "general, explore, build or plan"))
                        put("difficulty", JSONObject().put("type", "string").put("description", "Optional suggestion: SIMPLE, NORMAL, HARD, EXPERT. The orchestrator may raise it."))
                        put("capabilities", JSONObject().put("type", "array").put("description", "Required capabilities, e.g. coding, reasoning, tool_calling").put("items", JSONObject().put("type", "string")))
                        put("model", JSONObject().put("type", "string").put("description", "Optional preferred model ref, provider/model. Availability and capability are checked."))
                        put("depends_on", JSONObject().put("type", "array").put("description", "1-based task numbers this task waits for").put("items", JSONObject().put("type", "integer")))
                        put("timeout_seconds", JSONObject().put("type", "integer").put("description", "Per-task timeout, 5-900 seconds"))
                        put("context", JSONObject().put("type", "string").put("description", "Relevant file paths, errors or prior attempts"))
                        put("files", JSONObject().put("type", "array").put("description", "Files this task will modify; overlapping tasks are serialized").put("items", JSONObject().put("type", "string")))
                        put("tools", JSONObject().put("type", "array").put("description", "Optional tool allowlist").put("items", JSONObject().put("type", "string")))
                    })
                    put("required", JSONArray().put("description").put("prompt"))
                })
            })
        })
        put("required", JSONArray().put("tasks"))
    }

    override fun permissionKey(): String = "task"

    override fun resourcePattern(args: JSONObject, ctx: ToolContext): String = "delegate"

    override fun riskHint(args: JSONObject, ctx: ToolContext): RiskHint = RiskHint.BUILD

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val tasks = args.optJSONArray("tasks")
        if (tasks == null || tasks.length() == 0) {
            return ToolResult("Error: tasks must be a non-empty array", isError = true)
        }
        val output = ctx.bridge.delegateTasks(
            request = args,
            parentSessionId = ctx.sessionId,
            depth = ctx.depth,
            rootSessionId = ctx.rootSessionId,
            parentTaskId = ctx.orchestrationTaskId,
        )
        val failed = output.startsWith("DELEGATION FAILED") || output.startsWith("DELEGATION REJECTED")
        return ToolResult(output, isError = failed)
    }
}

class QuestionTool : OcTool {
    override val name = "question"

    override fun description(ctx: ToolContext): String =
        OcAssets.read(ctx.androidContext, "ai/prompt/tool/question.txt")
            ?: "Use this tool when you need to ask the user questions during execution."

    override fun parameters(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("questions", JSONObject().put("type", "array").put("description", "Questions to ask").put("items", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("question", JSONObject().put("type", "string").put("description", "Complete question"))
                    put("header", JSONObject().put("type", "string").put("description", "Very short label (max 30 chars)"))
                    put("multiple", JSONObject().put("type", "boolean").put("description", "Allow selecting multiple choices"))
                    put("options", JSONObject().put("type", "array").put("description", "Available choices").put("items", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("label", JSONObject().put("type", "string").put("description", "Display text (1-5 words, concise)"))
                            put("description", JSONObject().put("type", "string").put("description", "Explanation of choice"))
                        })
                        put("required", JSONArray().put("label"))
                    }))
                })
                put("required", JSONArray().put("question").put("header").put("options"))
            }))
        })
        put("required", JSONArray().put("questions"))
    }

    override fun permissionKey(): String = "question"

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val questions = args.optJSONArray("questions") ?: return ToolResult("Error: questions is required", isError = true)
        if (questions.length() == 0) return ToolResult("Error: questions is empty", isError = true)
        val answers = ctx.bridge.askQuestion(questions)
            ?: return ToolResult("User dismissed the questions. Stop and wait for the user's input.")
        return ToolResult("User has answered your questions: ${answers.toString()}")
    }
}

class SkillTool(private val skillsProvider: () -> OcSkill) : OcTool {
    override val name = "skill"

    override fun description(ctx: ToolContext): String =
        OcAssets.read(ctx.androidContext, "ai/prompt/tool/skill.txt")
            ?: "Load a specialized skill when the task at hand matches one of the skills listed in the system prompt."

    override fun parameters(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("name", JSONObject().put("type", "string").put("description", "The name of the skill from the available skills list"))
        })
        put("required", JSONArray().put("name"))
    }

    override fun permissionKey(): String = "skill"

    override fun resourcePattern(args: JSONObject, ctx: ToolContext): String = args.optString("name", "")

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val name = args.optString("name", "")
        if (name.isBlank()) return ToolResult("Error: name is required", isError = true)
        val skill = skillsProvider().get(name)
            ?: return ToolResult("Error: skill not found: $name. The skill name must match one of the skills listed in your system prompt.", isError = true)
        return ToolResult(skillsProvider().loadForTool(skill))
    }
}

private fun org.json.JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key, "").ifEmpty { null }
}
