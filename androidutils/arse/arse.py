#!/usr/bin/python
# -*- coding: utf-8 -*-

import XmlResourceChunk
import TableResourceChunk
import ResourceChunk
import sys
import xml.dom.ext

print >>sys.stderr, sys.argv[1]
test = ResourceChunk.ResourceChunkStream(open(sys.argv[1]))
for chunk in test.readChunks():
    if isinstance(chunk, XmlResourceChunk.XmlResourceChunk):
        print xml.dom.ext.PrettyPrint(chunk.XmlDoc)
    elif isinstance(chunk, TableResourceChunk.TableResourceChunk):
        print xml.dom.ext.PrettyPrint(chunk.XmlDoc)
