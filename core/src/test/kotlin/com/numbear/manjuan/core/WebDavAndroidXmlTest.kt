package com.numbear.manjuan.core

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

/**
 * Android's DocumentBuilderFactory rejects [XMLConstants.FEATURE_SECURE_PROCESSING]
 * on every API level. Desktop JVMs accept it, so this test installs a factory that
 * throws the same way the device parser does.
 */
class WebDavAndroidXmlTest {
    @Test
    fun testSucceedsAndListStillParsesWhenSecureProcessingIsUnsupported() {
        val xml = """
            <?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/dav/</d:href>
                <d:propstat><d:prop><d:displayname>dav</d:displayname><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav/dir/</d:href>
                <d:propstat><d:prop><d:displayname>dir</d:displayname><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav/b.txt</d:href>
                <d:propstat><d:prop><d:displayname>b.txt</d:displayname><d:getcontentlength>4</d:getcontentlength><d:resourcetype/></d:prop></d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()
        val key = "javax.xml.parsers.DocumentBuilderFactory"
        val previous = System.getProperty(key)
        System.setProperty(key, AndroidLikeDocumentBuilderFactory::class.java.name)
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(207).setBody(xml))
            server.enqueue(MockResponse().setResponseCode(207).setBody(xml))
            val client = OkHttpWebDavClient(server.url("/dav/").toString(), "user", "p@ss 密码")
            assertTrue(client.test() is WebDavStatus.Ok)
            assertEquals(listOf("dir", "b.txt"), client.list("").map { it.name })
        } finally {
            if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
            server.shutdown()
        }
    }
}

/**
 * Mirrors org.apache.harmony.xml.parsers.DocumentBuilderFactoryImpl: only the SAX
 * namespace and validation features are accepted.
 */
class AndroidLikeDocumentBuilderFactory : DocumentBuilderFactory() {
    private val delegate: DocumentBuilderFactory = run {
        val key = "javax.xml.parsers.DocumentBuilderFactory"
        val previous = System.getProperty(key)
        System.clearProperty(key)
        try {
            DocumentBuilderFactory.newInstance()
        } finally {
            if (previous != null) System.setProperty(key, previous)
        }
    }

    override fun newDocumentBuilder(): DocumentBuilder {
        delegate.isNamespaceAware = isNamespaceAware
        delegate.isValidating = isValidating
        delegate.isIgnoringElementContentWhitespace = isIgnoringElementContentWhitespace
        delegate.isExpandEntityReferences = isExpandEntityReferences
        delegate.isIgnoringComments = isIgnoringComments
        delegate.isCoalescing = isCoalescing
        return delegate.newDocumentBuilder()
    }

    override fun setAttribute(name: String, value: Any?) {
        delegate.setAttribute(name, value)
    }

    override fun getAttribute(name: String): Any = delegate.getAttribute(name)

    override fun setFeature(name: String, value: Boolean) {
        if (name == NAMESPACES) {
            isNamespaceAware = value
        } else if (name == VALIDATION) {
            isValidating = value
        } else {
            throw ParserConfigurationException(name)
        }
    }

    override fun getFeature(name: String): Boolean {
        if (name == NAMESPACES) return isNamespaceAware
        if (name == VALIDATION) return isValidating
        throw ParserConfigurationException(name)
    }

    private companion object {
        const val NAMESPACES = "http://xml.org/sax/features/namespaces"
        const val VALIDATION = "http://xml.org/sax/features/validation"
    }
}
