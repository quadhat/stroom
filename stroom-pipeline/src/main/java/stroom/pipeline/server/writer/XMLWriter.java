/*
 * Copyright 2016 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stroom.pipeline.server.writer;

import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import stroom.entity.server.util.XMLUtil;
import stroom.pipeline.server.LocationFactory;
import stroom.pipeline.server.errorhandler.ErrorListenerAdaptor;
import stroom.pipeline.server.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.server.errorhandler.LoggedException;
import stroom.pipeline.server.factory.ConfigurableElement;
import stroom.pipeline.server.factory.PipelineProperty;
import stroom.pipeline.server.filter.NullXMLFilter;
import stroom.pipeline.server.filter.XMLFilter;
import stroom.pipeline.shared.ElementIcons;
import stroom.pipeline.shared.data.PipelineElementType;
import stroom.pipeline.shared.data.PipelineElementType.Category;
import stroom.util.CharBuffer;

import javax.inject.Inject;
import javax.xml.transform.ErrorListener;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamResult;
import java.io.BufferedWriter;
import java.io.IOException;

/**
 * Writes out XML and records segment boundaries as it goes.
 */
@Component
@Scope("prototype")
@ConfigurableElement(type = "XMLWriter", category = Category.WRITER, roles = { PipelineElementType.ROLE_TARGET,
        PipelineElementType.ROLE_HAS_TARGETS, PipelineElementType.ROLE_WRITER,
        PipelineElementType.VISABILITY_STEPPING }, icon = ElementIcons.XML)
public class XMLWriter extends AbstractWriter implements XMLFilter {
    private final LocationFactory locationFactory;

    private ContentHandler handler = NullXMLFilter.INSTANCE;
    private Locator locator;

    private boolean doneElement;
    private int depth;
    private String rootElement;

    private boolean indentOutput = false;

    private byte[] header;
    private byte[] footer;

    private CharBufferWriter stringWriter;
    private BufferedWriter bufferedWriter;

    public XMLWriter() {
        this.locationFactory = null;
    }

    @Inject
    public XMLWriter(final ErrorReceiverProxy errorReceiverProxy, final LocationFactory locationFactory) {
        super(errorReceiverProxy);
        this.locationFactory = locationFactory;
    }

    @Override
    public void startProcessing() {
        try {
            stringWriter = new CharBufferWriter();
            bufferedWriter = new BufferedWriter(stringWriter);

            final ErrorListener errorListener = new ErrorListenerAdaptor(getElementId(), locationFactory,
                    getErrorReceiver());
            final TransformerHandler th = XMLUtil.createTransformerHandler(errorListener, indentOutput);
            th.setResult(new StreamResult(bufferedWriter));
            th.setDocumentLocator(locator);
            handler = th;

        } catch (final TransformerConfigurationException e) {
            fatal(e);
            throw new LoggedException(e.getMessage(), e);
        }

        super.startProcessing();
    }

    @Override
    public void startStream() {
        super.startStream();
        doneElement = false;
    }

    /**
     * @param locator
     *            an object that can return the location of any SAX document
     *            event
     *
     * @see org.xml.sax.Locator
     * @see stroom.pipeline.server.filter.AbstractXMLFilter#setDocumentLocator(org.xml.sax.Locator)
     */
    @Override
    public void setDocumentLocator(final Locator locator) {
        // Just remember the locator in case we start to output a document.
        this.locator = locator;
        super.setDocumentLocator(locator);
    }

    @Override
    public void startDocument() throws SAXException {
        handler.startDocument();
        super.startDocument();
    }

    @Override
    public void endDocument() throws SAXException {
        handler.endDocument();
        super.endDocument();
    }

    /**
     * @param prefix
     *            the Namespace prefix being declared. An empty string is used
     *            for the default element namespace, which has no prefix.
     * @param uri
     *            the Namespace URI the prefix is mapped to
     * @throws org.xml.sax.SAXException
     *             the client may throw an exception during processing
     *
     * @see #endPrefixMapping
     * @see #startElement
     * @see stroom.pipeline.server.filter.AbstractXMLFilter#startPrefixMapping(java.lang.String,
     *      java.lang.String)
     */
    @Override
    public void startPrefixMapping(final String prefix, final String uri) throws SAXException {
        handler.startPrefixMapping(prefix, uri);
        super.startPrefixMapping(prefix, uri);
    }

    /**
     * @param prefix
     *            the prefix that was being mapped. This is the empty string
     *            when a default mapping scope ends.
     * @throws org.xml.sax.SAXException
     *             the client may throw an exception during processing
     *
     * @see #startPrefixMapping
     * @see #endElement
     * @see stroom.pipeline.server.filter.AbstractXMLFilter#endPrefixMapping(java.lang.String)
     */
    @Override
    public void endPrefixMapping(final String prefix) throws SAXException {
        handler.endPrefixMapping(prefix);
        super.endPrefixMapping(prefix);
    }

    /**
     * @param uri
     *            the Namespace URI, or the empty string if the element has no
     *            Namespace URI or if Namespace processing is not being
     *            performed
     * @param localName
     *            the local name (without prefix), or the empty string if
     *            Namespace processing is not being performed
     * @param qName
     *            the qualified name (with prefix), or the empty string if
     *            qualified names are not available
     * @param atts
     *            the attributes attached to the element. If there are no
     *            attributes, it shall be an empty Attributes object. The value
     *            of this object after startElement returns is undefined
     * @throws org.xml.sax.SAXException
     *             any SAX exception, possibly wrapping another exception
     *
     * @see #endElement
     * @see org.xml.sax.Attributes
     * @see org.xml.sax.helpers.AttributesImpl
     * @see stroom.pipeline.server.filter.AbstractXMLFilter#startElement(java.lang.String,
     *      java.lang.String, java.lang.String, org.xml.sax.Attributes)
     */
    @Override
    public void startElement(final String uri, final String localName, final String qName, final Attributes atts)
            throws SAXException {
        try {
            // If depth is 1 then we are entering an event.
            if (rootElement == null) {
                rootElement = localName;
            }

            if (depth == 1) {
                bufferedWriter.flush();
                final CharBuffer cb = stringWriter.getBuffer();

                if (!doneElement) {
                    doneElement = true;

                    if (cb.length() > 0) {
                        // Compensate for the fact that the writer will not have
                        // received a closing bracket as it waits to see if the
                        // current start element will be an empty element. We
                        // will always write root start/end elements as full
                        // elements and not as <elem /> so add the close bracket
                        // here.
                        cb.append('>');

                        // Only allow the header and footer to be set once.
                        if (this.header == null && this.footer == null) {
                            final StringBuilder footerBuilder = new StringBuilder();
                            footerBuilder.append("</");
                            footerBuilder.append(rootElement);
                            footerBuilder.append(">");

                            if (indentOutput) {
                                cb.append('\n');
                                footerBuilder.append('\n');
                            }

                            final String header = cb.toString();
                            final String footer = footerBuilder.toString();

                            this.header = header.getBytes(getCharset());
                            this.footer = footer.getBytes(getCharset());
                        }
                    }
                }

                cb.setLength(0);
            }

            // Increase the element depth.
            depth++;

            handler.startElement(uri, localName, qName, atts);

        } catch (final IOException e) {
            throw new SAXException(e);
        } finally {
            super.startElement(uri, localName, qName, atts);
        }
    }

    /**
     * @param uri
     *            the Namespace URI, or the empty string if the element has no
     *            Namespace URI or if Namespace processing is not being
     *            performed
     * @param localName
     *            the local name (without prefix), or the empty string if
     *            Namespace processing is not being performed
     * @param qName
     *            the qualified XML name (with prefix), or the empty string if
     *            qualified names are not available
     * @throws org.xml.sax.SAXException
     *             any SAX exception, possibly wrapping another exception
     *
     * @see stroom.pipeline.server.filter.AbstractXMLFilter#endElement(java.lang.String,
     *      java.lang.String, java.lang.String)
     */
    @Override
    public void endElement(final String uri, final String localName, final String qName) throws SAXException {
        handler.endElement(uri, localName, qName);

        // Decrease the element depth
        depth--;

        try {
            if (depth <= 1) {
                bufferedWriter.flush();
                final CharBuffer cb = stringWriter.getBuffer();

                // If depth = 1 then we have finished an event.
                if (depth == 1) {
                    if (cb.length() > 0) {
                        // Compensate for the fact that the writer will now have
                        // received a closing bracket see comment in
                        // startElement() for an explanation.
                        cb.trimCharStart('>');

                        // Trim new lines off the character buffer.
                        cb.trimChar('\n');

                        // If we are indenting the output then add a new line
                        // after records.
                        if (indentOutput) {
                            cb.append('\n');
                        }

                        final String body = cb.toString();

                        borrowDestinations(header, footer);
                        getWriter().write(body);
                        returnDestinations();
                    }
                } else {
                    doneElement = false;
                }

                cb.setLength(0);
            }
        } catch (final IOException e) {
            throw new SAXException(e);
        }

        super.endElement(uri, localName, qName);
    }

    /**
     * @param ch
     *            the characters from the XML document
     * @param start
     *            the start position in the array
     * @param length
     *            the number of characters to read from the array
     * @throws org.xml.sax.SAXException
     *             any SAX exception, possibly wrapping another exception
     *
     * @see #ignorableWhitespace
     * @see org.xml.sax.Locator
     * @see stroom.pipeline.server.filter.AbstractXMLFilter#characters(char[],
     *      int, int)
     */
    @Override
    public void characters(final char[] ch, final int start, final int length) throws SAXException {
        handler.characters(ch, start, length);
        super.characters(ch, start, length);
    }

    /**
     * @param ch
     *            the characters from the XML document
     * @param start
     *            the start position in the array
     * @param length
     *            the number of characters to read from the array
     * @throws org.xml.sax.SAXException
     *             any SAX exception, possibly wrapping another exception
     *
     * @see #characters
     * @see stroom.pipeline.server.filter.AbstractXMLFilter#ignorableWhitespace(char[],
     *      int, int)
     */
    @Override
    public void ignorableWhitespace(final char[] ch, final int start, final int length) throws SAXException {
        handler.ignorableWhitespace(ch, start, length);
        super.ignorableWhitespace(ch, start, length);
    }

    /**
     * @param target
     *            the processing instruction target
     * @param data
     *            the processing instruction data, or null if none was supplied.
     *            The data does not include any whitespace separating it from
     *            the target
     * @throws org.xml.sax.SAXException
     *             any SAX exception, possibly wrapping another exception
     *
     * @see stroom.pipeline.server.filter.AbstractXMLFilter#processingInstruction(java.lang.String,
     *      java.lang.String)
     */
    @Override
    public void processingInstruction(final String target, final String data) throws SAXException {
        handler.processingInstruction(target, data);
        super.processingInstruction(target, data);
    }

    /**
     * @param name
     *            the name of the skipped entity. If it is a parameter entity,
     *            the name will begin with '%', and if it is the external DTD
     *            subset, it will be the string "[dtd]"
     * @throws org.xml.sax.SAXException
     *             any SAX exception, possibly wrapping another exception
     *
     * @see stroom.pipeline.server.filter.AbstractXMLFilter#skippedEntity(java.lang.String)
     */
    @Override
    public void skippedEntity(final String name) throws SAXException {
        handler.skippedEntity(name);
        super.skippedEntity(name);
    }

    @PipelineProperty(description = "Should output XML be indented and include new lines (pretty printed)?", defaultValue = "false")
    public void setIndentOutput(final boolean indentOutput) {
        this.indentOutput = indentOutput;
    }

    @Override
    @PipelineProperty(description = "The output character encoding to use.", defaultValue = "UTF-8")
    public void setEncoding(final String encoding) {
        super.setEncoding(encoding);
    }
}
