/*
 * Copyright 2009-2010 by The Regents of the University of California
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * you may obtain a copy of the License from
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.uci.ics.hyracks.storage.am.lsm.invertedindex.tokenizers;

import java.io.DataOutput;
import java.io.IOException;

import edu.uci.ics.hyracks.data.std.primitive.UTF8StringPointable;
import edu.uci.ics.hyracks.dataflow.common.data.util.StringUtils;

public class UTF8WordToken extends AbstractUTF8Token {

    public UTF8WordToken(byte tokenTypeTag, byte countTypeTag) {
        super(tokenTypeTag, countTypeTag);
    }

    @Override
    public void serializeToken(DataOutput dos) throws IOException {
        handleTokenTypeTag(dos);

        int tokenUTF8Len = getLowerCaseUTF8Len(tokenLength);
        StringUtils.writeUTF8Len(tokenUTF8Len, dos);
        int pos = start;
        for (int i = 0; i < tokenLength; i++) {
            char c = Character.toLowerCase(UTF8StringPointable.charAt(data, pos));
            StringUtils.writeCharAsModifiedUTF8(c, dos);
            pos += UTF8StringPointable.charSize(data, pos);
        }
    }
}
