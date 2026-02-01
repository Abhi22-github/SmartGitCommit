package com.example.smartgitcommit


import com.intellij.openapi.project.Project
import kotlinx.html.emptyMap
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date

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
            .map { it.substring(2).trim() } // Standardized way to strip Git status prefixes
    }

    fun commitSelectedFiles(
        project: Project,
        files: List<String>,
        message: String,
        dateTime: LocalDateTime
    ) {
        val root = project.basePath ?: return
        val formattedDate = dateTime.atZone(ZoneId.systemDefault()).toInstant().toString()

        files.forEach { absolutePath ->
            // Git works best with relative paths from the repo root
            val relativePath = File(root).toPath().relativize(File(absolutePath).toPath()).toString()

            val file = File(absolutePath)
            if (file.exists()) {
                // File exists on disk: Stage modification or new file
                run(root, listOf("git", "add", relativePath))
            } else {
                // File does NOT exist: Stage the deletion
                // --cached ensures it removes it from the index even if the file is gone
                run(root, listOf("git", "rm", "--cached", "-r", "--ignore-unmatch", relativePath))
            }
        }

        // Execute commit with Author and Committer date overrides
        run(
            root,
            listOf("git", "commit", "-m", message),
            mapOf(
                "GIT_AUTHOR_DATE" to formattedDate,
                "GIT_COMMITTER_DATE" to formattedDate
            )
        )
    }

    fun push(project: Project) {
        val root = project.basePath ?: return
        run(root, listOf("git", "push"))
    }

    fun runAndCapture(dir: String, command: List<String>): String {
        val pb = ProcessBuilder(command).directory(File(dir))
        val process = pb.start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return out
    }

    private fun run(dir: String, command: List<String>, env: Map<String, String> = emptyMap()) {
        val pb = ProcessBuilder(command).directory(File(dir))
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)

        val process = pb.start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw Exception("Git Error: $output")
        }
    }

    fun getLastCommitDate(proj: Project): Date? {
        val root = proj.basePath ?: return null
        return try {
            // %ct returns the committer date as a Unix timestamp
            val timestampStr = GitRunner.runAndCapture(root, listOf("git", "log", "-1", "--format=%ct")).trim()
            if (timestampStr.isNotEmpty()) {
                Date(timestampStr.toLong() * 1000)
            } else {
                null
            }
        } catch (e: Exception) {
            null // Likely a fresh repo with no commits yet
        }
    }
}