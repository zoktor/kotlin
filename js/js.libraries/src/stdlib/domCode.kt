package kotlin.dom

import org.w3c.dom.Document
import org.w3c.dom.Node

public fun createDocument(): Document {
    return html.document.implementation.createDocument(null, null, null)
}

/** Converts the node to an XML String */
public fun Node.toXmlString(): String = this.outerHTML

/** Converts the node to an XML String */
public fun Node.toXmlString(xmlDeclaration: Boolean): String = this.outerHTML
