/*
 * Knowage, Open Source Business Intelligence suite
 * Copyright (C) 2016 Engineering Ingegneria Informatica S.p.A.
 *
 * Knowage is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Knowage is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package it.eng.spagobi.commons.serializer;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.Locale;

import org.apache.log4j.Logger;
import org.json.JSONObject;

import it.eng.spagobi.analiticalmodel.document.bo.DocumentMetadataProperty;
import it.eng.spagobi.tools.objmetadata.bo.ObjMetacontent;
import it.eng.spagobi.tools.objmetadata.bo.ObjMetadata;

/**
 * @author Chiarelli Chiara
 */
public class MetadataJSONSerializer implements Serializer {

	private static Logger logger = Logger.getLogger(MetadataJSONSerializer.class);

	public static final String METADATA_ID = "meta_id";
	public static final String BIOBJECT_ID = "biobject_id";
	public static final String SUBOBJECT_ID = "subobject_id";
	public static final String NAME = "meta_name";
	public static final String TYPE = "meta_type";
	public static final String TEXT = "meta_content";
	public static final String CREATION_DATE = "meta_creation_date";
	public static final String CHANGE_DATE = "meta_change_date";

	@Override
	public Object serialize(Object o, Locale locale) throws SerializationException {
		logger.debug("IN");
		JSONObject result = new JSONObject();

		if ( !(o instanceof DocumentMetadataProperty) ) {
			throw new SerializationException("MetadataJSONSerializer is unable to serialize object of type: " + o.getClass().getName());
		}

		try {
			DocumentMetadataProperty both = (DocumentMetadataProperty)o;
			ObjMetadata meta = both.getMeta();
			ObjMetacontent content = both.getMetacontent();

			result.put(METADATA_ID, meta.getObjMetaId());
			result.put(NAME, meta.getName());
			result.put(TYPE, meta.getDataTypeCode());

			if (content != null) {
				String contentText = new String(content.getContent(),UTF_8);
				result.put(BIOBJECT_ID, content.getBiobjId());
				result.put(SUBOBJECT_ID, content.getSubobjId() != null ? content.getSubobjId() : -1);
				result.put(TEXT,contentText );
				result.put(CREATION_DATE, content.getCreationDate());
				result.put(CHANGE_DATE, content.getLastChangeDate());
			} else {
				result.put(BIOBJECT_ID, -1);
				result.put(SUBOBJECT_ID, -1);
				result.put(TEXT, "");
				result.put(CREATION_DATE, "");
				result.put(CHANGE_DATE, "");
			}
		} catch (Throwable t) {
			throw new SerializationException("An error occurred while serializing object: " + o, t);
		} finally {
			logger.debug("OUT");
		}
		return result;
	}

}
