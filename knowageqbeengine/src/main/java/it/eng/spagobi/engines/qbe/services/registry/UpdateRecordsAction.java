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
package it.eng.spagobi.engines.qbe.services.registry;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Vector;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.jamonapi.Monitor;
import com.jamonapi.MonitorFactory;

import it.eng.qbe.datasource.IDataSource;
import it.eng.spago.base.SourceBean;
import it.eng.spagobi.engines.qbe.QbeEngineInstance;
import it.eng.spagobi.engines.qbe.registry.bo.RegistryConfiguration;
import it.eng.spagobi.engines.qbe.services.core.AbstractQbeEngineAction;
import it.eng.spagobi.engines.qbe.services.initializers.RegistryEngineStartAction;
import it.eng.spagobi.utilities.assertion.Assert;
import it.eng.spagobi.utilities.engines.SpagoBIEngineServiceException;
import it.eng.spagobi.utilities.engines.SpagoBIEngineServiceExceptionHandler;
import it.eng.spagobi.utilities.exceptions.SpagoBIRuntimeException;
import it.eng.spagobi.utilities.service.JSONSuccess;

/**
 * @author Davide Zerbetto (davide.zerbetto@eng.it)
 *
 */

public class UpdateRecordsAction extends AbstractQbeEngineAction {

	private static final long serialVersionUID = -642121076148276452L;

	public static transient Logger logger = Logger.getLogger(UpdateRecordsAction.class);

	// INPUT PARAMETERS
	public static final String RECORDS = "records";

	String keyColumn;

	@Override
	public void service(SourceBean request, SourceBean response) {

		Monitor totalTimeMonitor = null;
		Monitor errorHitsMonitor = null;

		logger.debug("IN");

		try {

			super.service(request, response);

			totalTimeMonitor = MonitorFactory.start("QbeEngine.updateRecordsAction.totalTime");

			Vector<Integer> idsToReturn = executeUpdate();

			try {
				JSONArray arrays = new JSONArray();
				if (idsToReturn != null && idsToReturn.size() > 0) {
					for (Iterator iterator = idsToReturn.iterator(); iterator.hasNext();) {
						Integer integer = (Integer) iterator.next();
						arrays.put(integer);
					}
				}

				Map<String, Object> properties = new HashMap<>();
				properties.put("keyField", keyColumn);
				properties.put("ids", arrays);

				JSONObject jsonObject = new JSONObject(properties);

				// JSONAcknowledge jsonAcknowledge = new JSONAcknowledge();
				JSONSuccess success = new JSONSuccess(jsonObject);

				writeBackToClient(success);
			} catch (IOException e) {
				String message = "Impossible to write back the responce to the client";
				throw new SpagoBIEngineServiceException(getActionName(), message, e);
			}

		} catch (Throwable t) {
			errorHitsMonitor = MonitorFactory.start("QbeEngine.updateRecordsAction.errorHits");
			errorHitsMonitor.stop();
			throw SpagoBIEngineServiceExceptionHandler.getInstance().getWrappedException(getActionName(),
					getEngineInstance(), t);
		} finally {
			if (totalTimeMonitor != null)
				totalTimeMonitor.stop();
			logger.debug("OUT");
		}
	}

	private Vector<Integer> executeUpdate() throws Exception {
		QbeEngineInstance qbeEngineInstance = null;
		RegistryConfiguration registryConf = null;
		JSONArray modifiedRecords = null;
		Vector<Integer> idsToReturn = null;

		modifiedRecords = this.getAttributeAsJSONArray(RECORDS);
		logger.debug(modifiedRecords);
		if (modifiedRecords == null || modifiedRecords.length() == 0) {
			logger.warn("No records to update....");
			return null;
		}

		qbeEngineInstance = (QbeEngineInstance) getAttributeFromSession(RegistryEngineStartAction.ENGINE_INSTANCE);
		Assert.assertNotNull(qbeEngineInstance, "It's not possible to execute " + this.getActionName()
				+ " service before having properly created an instance of EngineInstance class");

		registryConf = qbeEngineInstance.getRegistryConfiguration();
		Assert.assertNotNull(registryConf, "It's not possible to execute " + this.getActionName()
				+ " service before having properly created an instance of RegistryConfiguration class");

		idsToReturn = new Vector<>();

		for (int i = 0; i < modifiedRecords.length(); i++) {
			JSONObject aRecord = modifiedRecords.getJSONObject(i);
			addDefaultValuesRecord(aRecord, qbeEngineInstance, registryConf);

			// if id field is null have to inserts
			IDataSource genericDatasource = qbeEngineInstance.getDataSource();
			keyColumn = genericDatasource.getPersistenceManager().getKeyColumn(registryConf);

			Object keyValueObject = null;
			if (aRecord.has(keyColumn))
				keyValueObject = aRecord.get(keyColumn);

			if (keyValueObject == null || keyValueObject.toString().equalsIgnoreCase("")) {
				logger.debug("Insert a new Row");

				// check if pk is autoload
				boolean autoLoadPK = false;
				String isAutoLoad = registryConf.getConfiguration(RegistryConfiguration.Configuration.IS_PK_AUTO_LOAD);
				if (isAutoLoad != null && isAutoLoad.equalsIgnoreCase("true")) {
					autoLoadPK = true;
				}

				String tableForPkMax = registryConf
						.getConfiguration(RegistryConfiguration.Configuration.TABLE_FOR_PK_MAX);
				String columnForPkMax = registryConf
						.getConfiguration(RegistryConfiguration.Configuration.COLUMN_FOR_PK_MAX);

				boolean enableAddRecords = registryConf
						.getConfiguration(RegistryConfiguration.Configuration.ENABLE_ADD_RECORDS) != null
						&& registryConf.getConfiguration(RegistryConfiguration.Configuration.ENABLE_ADD_RECORDS)
								.equalsIgnoreCase("true") ? true : false;
				boolean enableButtons = registryConf
						.getConfiguration(RegistryConfiguration.Configuration.ENABLE_BUTTONs) != null
						&& registryConf.getConfiguration(RegistryConfiguration.Configuration.ENABLE_BUTTONs)
								.equalsIgnoreCase("true") ? true : false;

				if (!enableAddRecords && !enableButtons) {
					String message = "You are not allowed to execute operation adding new records";
					throw new SpagoBIRuntimeException(message);
				}

				if (tableForPkMax == null || tableForPkMax.trim().equals(""))
					tableForPkMax = null;
				if (columnForPkMax == null || columnForPkMax.trim().equals(""))
					columnForPkMax = null;

				Integer id = insertRecord(aRecord, qbeEngineInstance, registryConf, autoLoadPK, tableForPkMax,
						columnForPkMax);
				idsToReturn.add(id);
			} else {
				logger.debug("Update Row with id " + keyColumn + " = " + keyValueObject.toString());
				updateRecord(aRecord, qbeEngineInstance, registryConf);

			}
		}

		return idsToReturn;

	}

	private void updateRecord(JSONObject aRecord, QbeEngineInstance qbeEngineInstance,
			RegistryConfiguration registryConf) {
		logger.debug("IN");
		IDataSource genericDatasource = qbeEngineInstance.getDataSource();
		genericDatasource.getPersistenceManager().updateRecord(aRecord, registryConf);
		logger.debug("OUT");
	}

	private Integer insertRecord(JSONObject aRecord, QbeEngineInstance qbeEngineInstance,
			RegistryConfiguration registryConf, boolean autoLoadPK, String tableForPkMax, String columnForPkMax) {
		logger.debug("IN");
		IDataSource genericDatasource = qbeEngineInstance.getDataSource();
		Integer id = genericDatasource.getPersistenceManager().insertRecord(aRecord, registryConf, autoLoadPK,
				tableForPkMax, columnForPkMax);
		logger.debug("OUT");
		return id;
	}

	private void addDefaultValuesRecord(JSONObject aRecord, QbeEngineInstance qbeEngineInstance,
			RegistryConfiguration registryConf) throws JSONException {
		IDataSource genericDatasource = qbeEngineInstance.getDataSource();
		genericDatasource.getPersistenceManager().addDefaultValueToRecord(aRecord, registryConf);
	}

}
