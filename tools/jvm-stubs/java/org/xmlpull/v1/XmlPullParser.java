package org.xmlpull.v1;

import java.io.InputStream;

/**
 * LOCAL-ONLY compile stub so tools/run-unit-tests.sh can compile data/Epg.kt without android.jar.
 * Only the members Epg.kt references. Nothing here runs: the local tests never parse XMLTV.
 */
public interface XmlPullParser {
    String FEATURE_PROCESS_NAMESPACES = "http://xmlpull.org/v1/doc/features.html#process-namespaces";
    int START_DOCUMENT = 0;
    int END_DOCUMENT = 1;
    int START_TAG = 2;
    int END_TAG = 3;
    int TEXT = 4;

    void setFeature(String name, boolean state) throws XmlPullParserException;
    void setInput(InputStream input, String encoding) throws XmlPullParserException;
    int getEventType() throws XmlPullParserException;
    int next() throws XmlPullParserException, java.io.IOException;
    String getName();
    String getText();
    int getDepth();
    String getAttributeValue(String namespace, String name);
}
