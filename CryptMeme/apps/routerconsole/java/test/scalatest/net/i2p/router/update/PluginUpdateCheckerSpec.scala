package net.i2p.router.update

import org.scalatest.FunSpec
import org.scalatest.mock.MockitoSugar

import java.net.URI
import java.util.Collections

import net.i2p.router.RouterContext

/**
 * @author str4d
 */
class PluginUpdateCheckerSpec extends FunSpec with UpdateRunnerBehaviors with MockitoSugar {
    def pluginUpdateChecker = {
        val mockCtx = mock[RouterContext]
        val mockMgr = mock[ConsoleUpdateManager]
        val mockUri = mock[URI]
        val uris = Collections.singletonList(mockUri)
        val puc = new PluginUpdateChecker(mockCtx, mockMgr, uris, "appName", "appVersion")
        puc
    }

    describe("A PluginUpdateChecker") {
        it should behave like updateRunner(pluginUpdateChecker)
    }
}
