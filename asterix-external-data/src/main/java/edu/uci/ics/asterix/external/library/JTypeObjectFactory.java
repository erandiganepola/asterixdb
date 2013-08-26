package edu.uci.ics.asterix.external.library;

import java.util.ArrayList;

import edu.uci.ics.asterix.external.library.java.IJObject;
import edu.uci.ics.asterix.external.library.java.JObjects.JBoolean;
import edu.uci.ics.asterix.external.library.java.JObjects.JCircle;
import edu.uci.ics.asterix.external.library.java.JObjects.JDate;
import edu.uci.ics.asterix.external.library.java.JObjects.JDateTime;
import edu.uci.ics.asterix.external.library.java.JObjects.JDouble;
import edu.uci.ics.asterix.external.library.java.JObjects.JDuration;
import edu.uci.ics.asterix.external.library.java.JObjects.JFloat;
import edu.uci.ics.asterix.external.library.java.JObjects.JInt;
import edu.uci.ics.asterix.external.library.java.JObjects.JInterval;
import edu.uci.ics.asterix.external.library.java.JObjects.JLine;
import edu.uci.ics.asterix.external.library.java.JObjects.JLong;
import edu.uci.ics.asterix.external.library.java.JObjects.JOrderedList;
import edu.uci.ics.asterix.external.library.java.JObjects.JPoint;
import edu.uci.ics.asterix.external.library.java.JObjects.JPoint3D;
import edu.uci.ics.asterix.external.library.java.JObjects.JPolygon;
import edu.uci.ics.asterix.external.library.java.JObjects.JRecord;
import edu.uci.ics.asterix.external.library.java.JObjects.JRectangle;
import edu.uci.ics.asterix.external.library.java.JObjects.JString;
import edu.uci.ics.asterix.external.library.java.JObjects.JTime;
import edu.uci.ics.asterix.external.library.java.JObjects.JUnorderedList;
import edu.uci.ics.asterix.om.types.AOrderedListType;
import edu.uci.ics.asterix.om.types.ARecordType;
import edu.uci.ics.asterix.om.types.AUnorderedListType;
import edu.uci.ics.asterix.om.types.IAType;
import edu.uci.ics.asterix.om.util.container.IObjectFactory;

public class JTypeObjectFactory implements IObjectFactory<IJObject, IAType> {

    @Override
    public IJObject create(IAType type) {
        IJObject retValue = null;
        switch (type.getTypeTag()) {
            case INT32:
                retValue = new JInt(0);
                break;
            case STRING:
                retValue = new JString("");
                break;
            case FLOAT:
                retValue = new JFloat(0);
                break;
            case DOUBLE:
                retValue = new JDouble(0);
                break;
            case BOOLEAN:
                retValue = new JBoolean(false);
                break;
            case CIRCLE:
                retValue = new JCircle(new JPoint(0, 0), 0);
                break;
            case POINT:
                retValue = new JPoint(0, 0);
                break;
            case POINT3D:
                retValue = new JPoint3D(0, 0, 0);
                break;
            case POLYGON:
                retValue = new JPolygon(new ArrayList<JPoint>());
                break;
            case LINE:
                retValue = new JLine(new JPoint(0, 0), new JPoint(0, 0));
                break;
            case RECTANGLE:
                retValue = new JRectangle(new JPoint(0, 0), new JPoint(1, 1));
                break;
            case DATE:
                retValue = new JDate(0);
                break;
            case DATETIME:
                retValue = new JDateTime(0);
                break;
            case DURATION:
                retValue = new JDuration(0, 0);
                break;
            case INTERVAL:
                retValue = new JInterval(0, 0);
                break;
            case TIME:
                retValue = new JTime(0);
                break;
            case INT64:
                retValue = new JLong(0);
                break;
            case ORDEREDLIST:
                AOrderedListType ot = (AOrderedListType) type;
                IAType orderedItemType = ot.getItemType();
                IJObject orderedItemObject = create(orderedItemType);
                retValue = new JOrderedList(orderedItemObject);
                break;
            case UNORDEREDLIST:
                AUnorderedListType ut = (AUnorderedListType) type;
                IAType unorderedItemType = ut.getItemType();
                IJObject unorderedItemObject = create(unorderedItemType);
                retValue = new JUnorderedList(unorderedItemObject);
                break;
            case RECORD:
                IAType[] fieldTypes = ((ARecordType) type).getFieldTypes();
                IJObject[] fieldObjects = new IJObject[fieldTypes.length];
                int index = 0;
                for (IAType fieldType : fieldTypes) {
                    fieldObjects[index] = create(fieldType);
                    index++;
                }
                retValue = new JRecord((ARecordType) type, fieldObjects);

                break;
        }
        return retValue;
    }
}