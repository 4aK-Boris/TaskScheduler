package cs.trade.scheduler.runner

import cs.trade.scheduler.runner.RunnerConfig.ArchiveS3Config
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Env→config parsing and validation for the optional S3 archival sink (DESIGN.md 18.7 / 14.5).
 * The actual `S3ArchivalSink` round-trip is covered by `:archival-s3`'s
 * `S3ArchivalSinkIntegrationTest`; the Koin binding (`archivalS3Module`) mirrors the
 * already-proven `EventBus` last-wins override in `Application.kt`.
 */
class RunnerConfigArchivalTest {

    @Test
    fun `fromEnv returns null when ARCHIVE_S3_BUCKET is unset`() {
        assertNull(ArchiveS3Config.fromEnv { null }, "no bucket → archival disabled")
        assertNull(ArchiveS3Config.fromEnv { name -> if (name == "ARCHIVE_S3_BUCKET") "  " else null }, "blank bucket → disabled")
    }

    @Test
    fun `fromEnv parses every ARCHIVE_S3 field`() {
        val env = mapOf(
            "ARCHIVE_S3_BUCKET" to "job-archive",
            "ARCHIVE_S3_REGION" to "eu-central-1",
            "ARCHIVE_S3_ENDPOINT" to "https://r2.example.com",
            "ARCHIVE_S3_ACCESS_KEY" to "AKIA",
            "ARCHIVE_S3_SECRET_KEY" to "secret",
            "ARCHIVE_S3_PATH_STYLE" to "true",
            "ARCHIVE_S3_KEY_PREFIX" to "prod",
        )
        val cfg = ArchiveS3Config.fromEnv { env[it] }!!
        assertEquals("job-archive", cfg.bucket)
        assertEquals("eu-central-1", cfg.region)
        assertEquals("https://r2.example.com", cfg.endpoint)
        assertEquals("AKIA", cfg.accessKeyId)
        assertEquals("secret", cfg.secretAccessKey)
        assertEquals(true, cfg.pathStyleAccess)
        assertEquals("prod", cfg.keyPrefix)
    }

    @Test
    fun `fromEnv applies defaults for the optional fields`() {
        val cfg = ArchiveS3Config.fromEnv { name -> if (name == "ARCHIVE_S3_BUCKET") "b" else null }!!
        assertEquals("us-east-1", cfg.region, "region defaults to us-east-1")
        assertNull(cfg.endpoint, "no endpoint → real AWS")
        assertNull(cfg.accessKeyId, "no creds → default chain")
        assertNull(cfg.secretAccessKey)
        assertNull(cfg.pathStyleAccess, "path-style left to the sink's auto rule")
        assertEquals("", cfg.keyPrefix)
    }

    @Test
    fun `validate accepts a well-formed S3 archival config`() {
        baseConfig(
            ArchiveS3Config(
                bucket = "b", region = "us-east-1", endpoint = "https://minio:9000",
                accessKeyId = "k", secretAccessKey = "s", pathStyleAccess = true, keyPrefix = "",
            ),
        ).validate()  // must not throw
    }

    @Test
    fun `validate rejects an endpoint without an http scheme`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            baseConfig(
                ArchiveS3Config("b", "us-east-1", "minio:9000", null, null, null, ""),
            ).validate()
        }
        assertTrue(ex.message!!.contains("ARCHIVE_S3_ENDPOINT"), ex.message)
    }

    @Test
    fun `validate rejects one credential half without the other`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            baseConfig(
                ArchiveS3Config("b", "us-east-1", null, "key-only", null, null, ""),
            ).validate()
        }
        assertTrue(ex.message!!.contains("ARCHIVE_S3_ACCESS_KEY"), ex.message)
    }

    /** A valid baseline config with the given archival settings, for validate() tests. */
    private fun baseConfig(archiveS3: ArchiveS3Config?): RunnerConfig = RunnerConfig(
        postgresUrl = "jdbc:postgresql://localhost:5432/scheduler",
        postgresUser = "scheduler",
        postgresPassword = "scheduler",
        rabbitHost = "localhost",
        rabbitPort = 5672,
        rabbitUser = "scheduler",
        rabbitPassword = "scheduler",
        rabbitVhost = "/",
        dashboardPort = 8080,
        dashboardAuthUser = "admin",
        dashboardAuthPassword = null,
        dashboardJwtSecret = null,
        dashboardJwtIssuer = null,
        dashboardJwtAudience = null,
        dashboardOidcIssuer = null,
        dashboardOidcJwksUrl = null,
        dashboardOidcAudience = null,
        nodeId = "test",
        runMigrations = true,
        archiveS3 = archiveS3,
    )
}
