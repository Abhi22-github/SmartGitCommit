package com.example.smartgitcommit


import com.intellij.openapi.project.Project
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
object GitRunner {

    /**
     * Commit all staged changes with custom date
     */
    fun commit(
        project: Project,
        message: String,
        dateTime: LocalDateTime
    ) {
        val root = project.basePath ?: return

        val formattedDate = formatDate(dateTime)

        // stage everything (or already staged files)
        run(root, listOf("git", "add", "."))

        // commit with backdated time
        run(
            root,
            listOf("git", "commit", "-m", message),
            env = mapOf(
                "GIT_AUTHOR_DATE" to formattedDate,
                "GIT_COMMITTER_DATE" to formattedDate
            )
        )
    }

    /**
     * Returns a list of unversioned (untracked) files in the repository root.
     * Uses `git ls-files --others --exclude-standard`.
     */
    fun getUnversionedFiles(project: Project): List<String> {
        val root = project.basePath ?: return emptyList()

        return try {
            val output = runAndCapture(root, listOf("git", "ls-files", "--others", "--exclude-standard"))
            output.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        } catch (e: Exception) {
            // If git fails for any reason, return an empty list rather than throwing
            emptyList()
        }
    }

    /**
     * Convenience: merge uncommitted (tracked) files and unversioned files into a single list.
     * Each entry is Pair(filePath, isUnversioned).
     * Tracked/uncommitted files come first, then unversioned files that are not already present.
     */
    fun getAllChanges(project: Project): List<Pair<String, Boolean>> {
        val tracked = try {
            getUncommittedFiles(project)
        } catch (e: Exception) {
            emptyList<String>()
        }

        val unversioned = try {
            getUnversionedFiles(project)
        } catch (e: Exception) {
            emptyList<String>()
        }

        val result = mutableListOf<Pair<String, Boolean>>()
        tracked.forEach { result.add(Pair(it, false)) }

        unversioned.forEach { uv ->
            if (tracked.none { it == uv }) {
                result.add(Pair(uv, true))
            }
        }

        return result
    }


    /**
     * Commit + Push (used by "Commit and Push…" button)
     */
    fun commitAndPush(
        project: Project,
        message: String,
        dateTime: LocalDateTime,
        push: Boolean
    ) {
        commit(project, message, dateTime)

        if (push) {
            val root = project.basePath ?: return
            run(root, listOf("git", "push"))
        }
    }

    private fun formatDate(dateTime: LocalDateTime): String {
        return dateTime
            .atZone(ZoneId.systemDefault())
            .toOffsetDateTime()
            .toString()
    }

    // ---------------- PUBLIC ----------------

    fun getUncommittedFiles(project: Project): List<String> {
        val root = project.basePath ?: return emptyList()

        val output = runAndCapture(root, listOf("git", "status", "--porcelain"))
        return output.lines()
            .filter { it.isNotBlank() }
            .map { it.substring(3) } // remove status prefix
    }

    fun commitSelectedFiles(
        project: Project,
        files: List<String>,
        message: String,
        dateTime: LocalDateTime,
        push: Boolean
    ) {
        val root = project.basePath ?: return

        val formattedDate = dateTime
            .atZone(ZoneId.systemDefault())
            .toOffsetDateTime()
            .toString()

        // Stage selected files only
        files.forEach {
            run(root, listOf("git", "add", it))
        }

        run(
            root,
            listOf("git", "commit", "-m", message),
            mapOf(
                "GIT_AUTHOR_DATE" to formattedDate,
                "GIT_COMMITTER_DATE" to formattedDate
            )
        )

        if (push) {
            run(root, listOf("git", "push"))
        }
    }

    // ---------------- INTERNAL ----------------

    private fun run(
        dir: String,
        command: List<String>,
        env: Map<String, String> = emptyMap()
    ) {
        val pb = ProcessBuilder(command)
            .directory(File(dir))

        pb.environment().putAll(env)
        pb.start().waitFor()
    }

    private fun runAndCapture(
        dir: String,
        command: List<String>
    ): String {
        val pb = ProcessBuilder(command)
            .directory(File(dir))

        val process = pb.start()
        return process.inputStream.bufferedReader().readText()
    }
}