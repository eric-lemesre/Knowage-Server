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
package it.eng.qbe.datasource.jpa;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.persistence.EntityManager;
import javax.persistence.EntityTransaction;
import javax.persistence.Query;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Root;
import javax.persistence.metamodel.Attribute;
import javax.persistence.metamodel.Attribute.PersistentAttributeType;
import javax.persistence.metamodel.EntityType;
import javax.persistence.metamodel.Metamodel;
import javax.persistence.metamodel.SingularAttribute;

import org.apache.log4j.Logger;
import org.json.JSONException;
import org.json.JSONObject;

import it.eng.qbe.datasource.IPersistenceManager;
import it.eng.qbe.datasource.jpa.audit.JPAPersistenceManagerAuditLogger;
import it.eng.qbe.datasource.jpa.audit.JPAPersistenceManagerInTableAudit;
import it.eng.qbe.datasource.jpa.audit.Operation;
import it.eng.qbe.logger.QueryAuditLogger;
import it.eng.qbe.model.structure.IModelEntity;
import it.eng.qbe.model.structure.IModelField;
import it.eng.qbe.query.CriteriaConstants;
import it.eng.qbe.query.ExpressionNode;
import it.eng.qbe.query.WhereField;
import it.eng.qbe.statement.AbstractStatement;
import it.eng.qbe.statement.IStatement;
import it.eng.qbe.statement.jpa.JPQLDataSet;
import it.eng.qbe.statement.jpa.JPQLStatement;
import it.eng.spago.error.EMFInternalError;
import it.eng.spagobi.commons.bo.UserProfile;
import it.eng.spagobi.commons.utilities.StringUtilities;
import it.eng.spagobi.engines.qbe.registry.bo.RegistryConfiguration;
import it.eng.spagobi.engines.qbe.registry.bo.RegistryConfiguration.Column;
import it.eng.spagobi.user.UserProfileManager;
import it.eng.spagobi.utilities.assertion.Assert;
import it.eng.spagobi.utilities.exceptions.SpagoBIRuntimeException;

public class JPAPersistenceManager implements IPersistenceManager {

	private JPADataSource dataSource;

	private static final Logger logger = Logger.getLogger(JPAPersistenceManager.class);

	private static final Logger auditlogger = QueryAuditLogger.LOGGER;

	public static final String TIMESTAMP_SIMPLE_FORMAT = "dd/MM/yyyy HH:mm:ss";

	public static final String TIMESTAMP_ISO_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSSZ";

	public JPAPersistenceManager(JPADataSource dataSource) {
		this.dataSource = dataSource;
	}

	public JPADataSource getDataSource() {
		return dataSource;
	}

	public void setDataSource(JPADataSource dataSource) {
		this.dataSource = dataSource;
	}

	@Override
	public String getKeyColumn(RegistryConfiguration registryConf) {
		String toReturn = null;

		logger.debug("IN");
		EntityManager entityManager = null;
		try {
			Assert.assertNotNull(registryConf, "Input parameter [registryConf] cannot be null");

			logger.debug("Target entity: " + registryConf.getEntity());

			entityManager = dataSource.getEntityManager();
			Assert.assertNotNull(entityManager, "entityManager cannot be null");

			EntityType targetEntity = getTargetEntity(registryConf, entityManager);
			String keyAttributeName = getKeyAttributeName(targetEntity);
			logger.debug("Key attribute name is equal to " + keyAttributeName);

			toReturn = keyAttributeName;

		} catch (Exception e) {
			logger.error(e);
			throw new SpagoBIRuntimeException("Error searching for key column", e);
		}

		logger.debug("OUT");
		return toReturn;
	}

	private synchronized Integer getPKValue(EntityType targetEntity, String keyColumn, EntityManager entityManager) {
		logger.debug("IN");
		Integer toReturn = 0;
		String name = targetEntity.getName();

		logger.debug("SELECT max(p." + keyColumn + ") as c FROM " + targetEntity.getName() + " p");

		CriteriaBuilder cb1 = entityManager.getCriteriaBuilder();
		@SuppressWarnings("unchecked")
		CriteriaQuery<Number> cq = cb1.createQuery(Number.class);
		@SuppressWarnings("unchecked")
		Root root = cq.from(targetEntity.getBindableJavaType());
		//Number num = (Number) cq.select(cb1.max(root.<Number>get(keyColumn)));

		//Query maxQuery = entityManager.createQuery("SELECT max(p." + keyColumn + ") as c FROM " + targetEntity.getName() + " p");
		Query maxQuery = entityManager.createQuery(cq.select(cb1.max(root.<Number>get(keyColumn))));


		//Object result = maxQuery.getSingleResult();
		Number num = (Number) maxQuery.getSingleResult();

		if (num != null) {
			toReturn = num.intValue();
			toReturn++;
		}

		logger.debug("New PK is " + toReturn);
		logger.debug("OUT");
		return toReturn;
	}

	private synchronized Integer getPKValueFromTemplateTable(String tableName, String keyColumn,
			EntityManager entityManager) {
		logger.debug("IN");
		Integer toReturn = 0;

		Query maxQuery = entityManager.createQuery(String.format("SELECT max(p.%s) as c FROM %s p", keyColumn, tableName));

		Object result = maxQuery.getSingleResult();

		if (result != null) {
			toReturn = Integer.valueOf(result.toString());
			toReturn++;
		}

		logger.debug("New PK taken from table " + tableName + " is " + toReturn);
		logger.debug("OUT");
		return toReturn;
	}

	@Override
	public Integer insertRecord(JSONObject aRecord, RegistryConfiguration registryConf, boolean autoLoadPK,
			String tableForPkMax, String columnForPkMax) {

		EntityTransaction entityTransaction = null;
		Integer toReturn = null;

		logger.debug("IN");
		EntityManager entityManager = null;
		try {
			Assert.assertNotNull(aRecord, "Input parameter [record] cannot be null");
			Assert.assertNotNull(aRecord, "Input parameter [registryConf] cannot be null");

			logger.debug("New record: " + aRecord.toString(3));
			logger.debug("Target entity: " + registryConf.getEntity());

			entityManager = dataSource.getEntityManager();
			Assert.assertNotNull(entityManager, "entityManager cannot be null");

			entityTransaction = entityManager.getTransaction();

			EntityType targetEntity = getTargetEntity(registryConf, entityManager);
			String keyAttributeName = getKeyAttributeName(targetEntity);
			logger.debug("Key attribute name is equal to " + keyAttributeName);
			// targetEntity.getI

			Iterator it = aRecord.keys();

			Class classToCreate = targetEntity.getJavaType();

			Object newObj = classToCreate.newInstance();
			logger.debug("Key column class is equal to [" + newObj.getClass().getName() + "]");

			while (it.hasNext()) {
				String attributeName = (String) it.next();
				logger.debug("Processing column [" + attributeName + "] ...");

				if (keyAttributeName.equals(attributeName)) {
					logger.debug("Skip column [" + attributeName + "] because it is the key of the table");
					continue;
				}
				Column column = registryConf.getColumnConfiguration(attributeName);

				if (column.getSubEntity() != null) {
					logger.debug("Column [" + attributeName + "] is a foreign key");
					if (aRecord.get(attributeName) != null && !aRecord.get(attributeName).equals("")) {
						logger.debug("search foreign reference for value " + aRecord.get(attributeName));
						Map<String, Object> subEntityProperties = getAllSubEntityProperties(aRecord, registryConf,
								column.getSubEntity());
						setSubEntity(targetEntity, column, newObj, subEntityProperties, entityManager);
					} else {
						// no value in column, insert null
						logger.debug("No value for " + attributeName + ": keep it null");
					}

				} else {
					logger.debug("Column [" + attributeName + "] is a normal column");
					setProperty(targetEntity, newObj, attributeName, aRecord.get(attributeName));
				}
			}

			// calculate PK
			if (true || !autoLoadPK) {

				Integer pkValue = null;
				// check if an alternative table and column has been specified
				// to retrieve PK
				String keyColumn = getKeyColumn(registryConf);
				if (tableForPkMax != null && columnForPkMax != null) {
					logger.debug("Retrieve PK as max+1 from table: " + tableForPkMax + " / column: " + columnForPkMax);
					pkValue = getPKValueFromTemplateTable(tableForPkMax, columnForPkMax, entityManager);
					setKeyProperty(targetEntity, newObj, keyColumn, pkValue);
				} else {
					logger.debug("calculate max value +1 for key column " + keyColumn + " in table "
							+ targetEntity.getName());
					pkValue = getPKValue(targetEntity, keyColumn, entityManager);
					setKeyProperty(targetEntity, newObj, keyColumn, pkValue);
				}

				if (pkValue == null) {
					logger.error("could not retrieve pk ");
					throw new Exception("could not retrieve pk for table " + targetEntity.getName());
				}

				toReturn = pkValue;
			}

			// @formatter:off
			JPAPersistenceManagerInTableAudit jpaPersistenceManagerInTableAudit = JPAPersistenceManagerInTableAudit.builder()
					.withJPAPersistenceManager(this)
					.withRegistryConfiguration(registryConf)
					.withUserProfile(UserProfileManager.getProfile())
					.build();
			// @formatter:on
			jpaPersistenceManagerInTableAudit.auditInsertion(targetEntity, newObj);

			if (!entityTransaction.isActive()) {
				entityTransaction.begin();
			}

			entityManager.persist(newObj);
			entityManager.flush();
			entityTransaction.commit();

			JPAPersistenceManagerAuditLogger.log(Operation.INSERTION,
					this.getDataSource().getConfiguration().getModelName(), registryConf.getEntity(), null, aRecord,
					null);

		} catch (Exception e) {
			if (entityTransaction != null && entityTransaction.isActive()) {
				entityTransaction.rollback();
			}
			logger.error(e);
			throw new SpagoBIRuntimeException("Error saving entity", e);
		} finally {
			logger.debug("OUT");
		}
		return toReturn;
	}

	@Override
	public void updateRecord(JSONObject aRecord, RegistryConfiguration registryConf) {

		EntityTransaction entityTransaction = null;

		logger.debug("IN");
		EntityManager entityManager = null;
		try {
			Assert.assertNotNull(aRecord, "Input parameter [record] cannot be null");
			Assert.assertNotNull(aRecord, "Input parameter [registryConf] cannot be null");

			logger.debug("New record: " + aRecord.toString(3));
			logger.debug("Target entity: " + registryConf.getEntity());

			entityManager = dataSource.getEntityManager();
			Assert.assertNotNull(entityManager, "entityManager cannot be null");

			entityTransaction = entityManager.getTransaction();

			EntityType targetEntity = getTargetEntity(registryConf, entityManager);
			String keyAttributeName = getKeyAttributeName(targetEntity);
			logger.debug("Key attribute name is equal to " + keyAttributeName);

			Iterator it = aRecord.keys();

			Object keyColumnValue = aRecord.get(keyAttributeName);
			logger.debug("Key of new record is equal to " + keyColumnValue);
			logger.debug("Key column java type equal to [" + targetEntity.getJavaType() + "]");
			Attribute a = targetEntity.getAttribute(keyAttributeName);
			Object obj = entityManager.find(targetEntity.getJavaType(), this.convertValue(keyColumnValue, a));
			logger.debug("Key column class is equal to [" + obj.getClass().getName() + "]");

			// object used to track old values, just before changes
			JSONObject oldRecord = new JSONObject();
			oldRecord.put(keyAttributeName, this.convertValue(keyColumnValue, a));
			// just to count the number of changes
			int changesCounter = 0;
			// list used to track processed attributes
			List<String> processedAttributes = new ArrayList<>();

			while (it.hasNext()) {
				String attributeName = (String) it.next();
				logger.debug("Processing column [" + attributeName + "] ...");

				if (keyAttributeName.equals(attributeName)) {
					logger.debug("Skip column [" + attributeName + "] because it is the key of the table");
					continue;
				}

				Column column = registryConf.getColumnConfiguration(attributeName);

				if (!column.isEditable() || column.isInfoColumn() || column.isAudit()) {
					logger.debug("Skip column [" + attributeName
							+ "] because it is not editable or it is an info or audit column");
					continue;
				}

				if (processedAttributes.contains(attributeName)) {
					logger.debug("Skip column [" + attributeName + "] because it was already processed");
					continue;
				}

				if (column.getSubEntity() != null) {
					logger.debug("Column [" + attributeName + "] is a foreign key");

					Map<String, Object> subEntityProperties = getAllSubEntityProperties(aRecord, registryConf,
							column.getSubEntity());

					int changed = updateSubEntity(subEntityProperties, entityManager, targetEntity, obj, oldRecord,
							column);
					if (changed > 0) {
						changesCounter += changed;
					}
					// adding sub-entity attributes into the list of processed attributes
					subEntityProperties.keySet().forEach(processedAttributes::add);
				} else {
					logger.debug("Column [" + attributeName + "] is a normal column");
					boolean changed = updateProperty(aRecord, targetEntity, obj, oldRecord, attributeName);
					if (changed) {
						changesCounter++;
					}
				}

				processedAttributes.add(attributeName);
			}

			// @formatter:off
			JPAPersistenceManagerInTableAudit jpaPersistenceManagerInTableAudit = JPAPersistenceManagerInTableAudit.builder()
					.withJPAPersistenceManager(this)
					.withRegistryConfiguration(registryConf)
					.withUserProfile(UserProfileManager.getProfile())
					.build();
			// @formatter:on
			jpaPersistenceManagerInTableAudit.auditUpdate(aRecord, targetEntity, obj);

			if (!entityTransaction.isActive()) {
				entityTransaction.begin();
			}

			entityManager.persist(obj);
			entityManager.flush();
			entityTransaction.commit();

			JPAPersistenceManagerAuditLogger.log(Operation.UPDATE,
					this.getDataSource().getConfiguration().getModelName(), registryConf.getEntity(), oldRecord,
					aRecord, changesCounter);

		} catch (Exception e) {
			if (entityTransaction != null && entityTransaction.isActive()) {
				entityTransaction.rollback();
			}
			logger.error(e);
			throw new SpagoBIRuntimeException("Error saving entity", e);
		} finally {
			logger.debug("OUT");
		}

	}

	private Map<String, Object> getAllSubEntityProperties(JSONObject aRecord, RegistryConfiguration registryConf,
			String subEntity) {
		List<Column> columns = registryConf.getColumns();
		// @formatter:off
		Map<String, Object> allSubEntityProperties =
				columns.stream()
				.filter(column -> subEntity.equals(column.getSubEntity()))
				.filter(column -> column.isVisible())
				.filter(column -> column.isEditable())// we get all columns belonging to same sub-entity
				.collect(Collectors.toMap(Column::getField, column -> aRecord.opt(column.getField()))); // we create a map (column field -> incoming value)
		// @formatter:on
		for (Column column : columns) {
			if (subEntity.equals(column.getSubEntity()) && column.isVisible() && column.isEditable()) {
				String dep = column.getDependences();
				if (dep != null && !allSubEntityProperties.containsKey(dep)) {
	                allSubEntityProperties.put(dep, aRecord.opt(dep));
	            }
			}
		}
		return allSubEntityProperties;
	}

	/**
	 *
	 * @return true if sub-entity was changed, false otherwise
	 * @throws JSONException
	 */
	protected int updateSubEntity(Map<String, Object> newSubEntityAttributes, EntityManager entityManager,
			EntityType targetEntity, Object obj, JSONObject oldRecord, Column column) throws JSONException {
		EntityType subEntityType = getSubEntityType(targetEntity, column.getSubEntity(), entityManager);
		Object subEntity = getOldSubEntity(targetEntity, column, obj);
		int counter = 0;
		for (Map.Entry<String, Object> entry : newSubEntityAttributes.entrySet()) {
			String attributeName = entry.getKey();
			Object newAttributeValue = entry.getValue();
			Object oldValue = getOldProperty(subEntityType, subEntity, entry.getKey());
			oldRecord.put(attributeName, oldValue);
			if (!areEquals(oldValue, newAttributeValue)) {
				counter++;
			}
		}
		setSubEntity(targetEntity, column, obj, newSubEntityAttributes, entityManager);
		return counter;
	}

	/**
	 *
	 * @return true if property was changed, false otherwise
	 * @throws JSONException
	 */
	protected boolean updateProperty(JSONObject aRecord, EntityType targetEntity, Object obj, JSONObject oldRecord,
			String attributeName) throws JSONException {
		Object oldValue = getOldProperty(targetEntity, obj, attributeName);
		oldRecord.put(attributeName, oldValue);
		Object newValue = aRecord.get(attributeName);
		setProperty(targetEntity, obj, attributeName, newValue);
		Attribute attribute = targetEntity.getAttribute(attributeName);
		Object newValueConverted = this.convertValue(newValue, attribute);
		if (!areEquals(oldValue, newValueConverted)) {
			return true;
		}
		return false;
	}

	private EntityType getSubEntityType(EntityType targetEntity, String subEntity, EntityManager entityManager) {
		Attribute a = targetEntity.getAttribute(subEntity);
		Attribute.PersistentAttributeType type = a.getPersistentAttributeType();
		if (type.equals(PersistentAttributeType.MANY_TO_ONE)) {
			String entityJavaSimpleName = a.getJavaType().getSimpleName();
			return getEntityByName(entityManager, entityJavaSimpleName);
		} else {
			throw new SpagoBIRuntimeException("Property " + subEntity + " is not a many-to-one relation");
		}
	}

	private Object getOldSubEntity(EntityType targetEntity, Column column, Object obj) {

		Attribute a = targetEntity.getAttribute(column.getSubEntity());

		Attribute.PersistentAttributeType type = a.getPersistentAttributeType();
		if (type.equals(PersistentAttributeType.MANY_TO_ONE)) {
			String subKey = a.getName();
			try {
				Class clazz = targetEntity.getJavaType();
				Object subEntity = new PropertyDescriptor(subKey, clazz).getReadMethod().invoke(obj);
				return subEntity;
			} catch (Exception e) {
				throw new SpagoBIRuntimeException(
						"Error while getting sub entity " + column.getSubEntity() + " from entity " + targetEntity, e);
			}
		} else {
			throw new SpagoBIRuntimeException("Property " + column.getSubEntity() + " is not a many-to-one relation");
		}
	}

	private boolean areEquals(Object oldValue, Object newValue) {
		if (oldValue == null && newValue == null) {
			// both are null, return true
			return true;
		}
		if (oldValue == null || newValue == null) {
			// one of the 2 is null but not both
			return false;
		}
		// compare values
		return oldValue.equals(newValue);
	}

	@Override
	public void deleteRecord(JSONObject aRecord, RegistryConfiguration registryConf) {

		EntityTransaction entityTransaction = null;

		logger.debug("IN");
		EntityManager entityManager = null;
		try {
			Assert.assertNotNull(aRecord, "Input parameter [record] cannot be null");
			Assert.assertNotNull(aRecord, "Input parameter [registryConf] cannot be null");

			logger.debug("Record: " + aRecord.toString(3));
			logger.debug("Target entity: " + registryConf.getEntity());

			entityManager = dataSource.getEntityManager();
			Assert.assertNotNull(entityManager, "entityManager cannot be null");

			entityTransaction = entityManager.getTransaction();

			EntityType targetEntity = getTargetEntity(registryConf, entityManager);
			String keyAttributeName = getKeyAttributeName(targetEntity);
			logger.debug("Key attribute name is equal to " + keyAttributeName);

			Iterator it = aRecord.keys();

			Object keyColumnValue = aRecord.get(keyAttributeName);
			logger.debug("Key of record is equal to " + keyColumnValue);
			logger.debug("Key column java type equal to [" + targetEntity.getJavaType() + "]");
			Attribute a = targetEntity.getAttribute(keyAttributeName);
			Object obj = entityManager.find(targetEntity.getJavaType(), this.convertValue(keyColumnValue, a));
			logger.debug("Key column class is equal to [" + obj.getClass().getName() + "]");

			if (!entityTransaction.isActive()) {
				entityTransaction.begin();
			}

			String q = String.format("DELETE from %s WHERE %s=%s", targetEntity.getName(), keyAttributeName,
					Optional.ofNullable(keyColumnValue).map(x -> x.toString()).orElse(null));
			logger.debug("create Query " + q);
			Query deleteQuery = entityManager.createQuery(q);

			int deleted = deleteQuery.executeUpdate();

			// entityManager.remove(obj);
			// entityManager.flush();
			entityTransaction.commit();

			JPAPersistenceManagerAuditLogger.log(Operation.DELETION,
					this.getDataSource().getConfiguration().getModelName(), registryConf.getEntity(), aRecord, null,
					null);

		} catch (Exception e) {
			if (entityTransaction != null && entityTransaction.isActive()) {
				entityTransaction.rollback();
			}
			logger.error(e);
			throw new SpagoBIRuntimeException("Error deleting entity", e);
		} finally {
			Optional.ofNullable(entityManager).ifPresent(x -> x.clear());
			logger.debug("OUT");
		}

	}

	public EntityType getTargetEntity(RegistryConfiguration registryConf, EntityManager entityManager) {
		String targetEntityName = getTargetEntityName(registryConf);
		return getEntityByName(entityManager, targetEntityName);
	}

	protected EntityType getEntityByName(EntityManager entityManager, String entityName) {
		EntityType toReturn = null;

		Metamodel classMetadata = entityManager.getMetamodel();
		Iterator it = classMetadata.getEntities().iterator();

		while (it.hasNext()) {
			EntityType entity = (EntityType) it.next();
			String jpaEntityName = entity.getName();

			if (entity != null && jpaEntityName.equals(entityName)) {
				toReturn = entity;
				break;
			}
		}

		return toReturn;
	}

	public String getKeyAttributeName(EntityType entity) {
		logger.debug("IN : entity = [" + entity + "]");
		String keyName = null;
		for (Object attribute : entity.getAttributes()) {
			if (attribute instanceof SingularAttribute) {
				SingularAttribute s = (SingularAttribute) attribute;
				logger.debug("Attribute: " + s.getName() + " is a singular attribute.");
				if (s.isId()) {
					keyName = s.getName();
					break;
				}
			} else {
				logger.debug("Attribute " + attribute + " is not singular attribute, cannot manage it");
			}
		}
		Assert.assertNotNull(keyName, "Key attribute name was not found!");
		logger.debug("OUT : " + keyName);
		return keyName;
	}

	public Object getOldProperty(EntityType targetEntity, Object obj, String aKey) {

		logger.debug("IN");

		try {
			Attribute a = targetEntity.getAttribute(aKey);
			Class clazz = targetEntity.getJavaType();
			Object property = new PropertyDescriptor(aKey, clazz).getReadMethod().invoke(obj);
			return property;
		} catch (Exception e) {
			throw new SpagoBIRuntimeException("Error while getting property " + aKey + " from entity " + targetEntity,
					e);
		} finally {
			logger.debug("OUT");
		}
	}

	// case of foreign key
	private void setSubEntity(EntityType targetEntity, Column c, Object obj, Map<String, Object> mewSubEntityAttributes,
			EntityManager entityManager) {

		Attribute a = targetEntity.getAttribute(c.getSubEntity());

		Attribute.PersistentAttributeType type = a.getPersistentAttributeType();
		if (type.equals(PersistentAttributeType.MANY_TO_ONE)) {
			String entityJavaType = a.getJavaType().getName();
			String subKey = a.getName();
			try {

				Object referenced = getReferencedObjectJPA(entityManager, entityJavaType, mewSubEntityAttributes);

				Class clas = targetEntity.getJavaType();
				Field f = clas.getDeclaredField(subKey);
				f.setAccessible(true);
				// entityManager.refresh(referenced);
				f.set(obj, referenced);
			} catch (Exception e) {
				throw new SpagoBIRuntimeException("Error setting sub-entity", e);
			}
		} else {
			throw new SpagoBIRuntimeException("Property " + c.getSubEntity() + " is not a many-to-one relation");
		}
	}

	public void setProperty(EntityType targetEntity, Object obj, String aKey, Object newValue) {
		logger.debug("IN");
		try {
			Attribute a = targetEntity.getAttribute(aKey);
			Class clas = targetEntity.getJavaType();
			Field f = clas.getDeclaredField(aKey);
			f.setAccessible(true);
			Object valueConverted = this.convertValue(newValue, a);
			f.set(obj, valueConverted);
		} catch (Exception e) {
			throw new SpagoBIRuntimeException("Error setting Field " + aKey + "", e);
		} finally {
			logger.debug("OUT");
		}
	}

	private void setKeyProperty(EntityType targetEntity, Object obj, String aKey, Integer value) {

		logger.debug("IN");

		try {
			Attribute a = targetEntity.getAttribute(aKey);
			Class clas = targetEntity.getJavaType();
			Field f = clas.getDeclaredField(aKey);
			f.setAccessible(true);
			Object valueConverted = this.convertValue(value, a);
			f.set(obj, valueConverted);
		} catch (Exception e) {
			throw new SpagoBIRuntimeException("Error setting Field " + aKey + "", e);
		} finally {
			logger.debug("OUT");
		}
	}

	private String getTargetEntityName(RegistryConfiguration registryConf) {
		String entityName = registryConf.getEntity();
		int lastPkgDot = entityName.lastIndexOf(".");
		String entityNameNoPkg = entityName.substring(lastPkgDot + 1);
		return entityNameNoPkg;
	}

	private Object convertValue(Object valueObj, Attribute attribute) {
		if (valueObj == null) {
			return null;
		}
		String value = valueObj.toString();
		Object toReturn = null;

		Class clazz = attribute.getJavaType();
		String clazzName = clazz.getName();

		if (Number.class.isAssignableFrom(clazz)) {
			if (value.equals("NaN") || value.equals("null") || value.equals("")) {
				toReturn = null;
				return toReturn;
			}
			// BigInteger, Integer, Long, Short, Byte
			if (Integer.class.getName().equals(clazzName)) {
				toReturn = Integer.parseInt(value);
			} else if (Double.class.getName().equals(clazzName)) {
				toReturn = new Double(value);
			} else if (BigDecimal.class.getName().equals(clazzName)) {
				toReturn = new BigDecimal(value);
			} else if (BigInteger.class.getName().equals(clazzName)) {
				toReturn = new BigInteger(value);
			} else if (Long.class.getName().equals(clazzName)) {
				toReturn = new Long(value);
			} else if (Short.class.getName().equals(clazzName)) {
				toReturn = new Short(value);
			} else if (Byte.class.getName().equals(clazzName)) {
				toReturn = new Byte(value);
			} else {
				toReturn = new Float(value);
			}
		} else if (String.class.isAssignableFrom(clazz)) {
			if (value.equals("")) {
				toReturn = null;
			} else {
				toReturn = value;
			}
		} else if (Timestamp.class.isAssignableFrom(clazz)) {
			Date date;
			SimpleDateFormat sdf = new SimpleDateFormat(TIMESTAMP_SIMPLE_FORMAT);
			SimpleDateFormat sdfISO = new SimpleDateFormat(TIMESTAMP_ISO_FORMAT);
			// SimpleDateFormat sdf = new
			// SimpleDateFormat("MM/dd/yyyy hh:mm:ss");
			if (value.equals("") || value.toLowerCase().equals("null")) {
				return null;
			}
			if (!value.equals("") && !value.contains(":")) {
				value += " 00:00:00";
			}
			try {
				if (value.contains("Z")) {
					date = sdfISO.parse(value.replaceAll("Z$", "+0000"));
					toReturn = new Timestamp(date.getTime());
				} else {
					date = sdf.parse(value);
					toReturn = new Timestamp(date.getTime());
				}
			} catch (ParseException e) {
				logger.error("Unparsable timestamp", e);
			}

		} else if (Date.class.isAssignableFrom(clazz)) {

			if (value.equals("") || value.toLowerCase().equals("null")) {
				return null;
			}

			SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

			try {
				toReturn = new java.sql.Date(sdf.parse(value).getTime());
			} catch (ParseException e) {
				logger.error("Unparsable date", e);
			}

		} else if (Boolean.class.isAssignableFrom(clazz)) {
			toReturn = Boolean.parseBoolean(value);
		} else if (Clob.class.isAssignableFrom(clazz)) {
			try {
				Connection connection = dataSource.getToolsDataSource().getConnection();
				toReturn = connection.createClob();
				((Clob) toReturn).setString(1, value);
			} catch (Exception e) {
				logger.error("Error in creating clob object", e);
				toReturn = null;
			}
		} else {
			toReturn = value;
		}

		return toReturn;
	}

	private Object getReferencedObjectJPA(EntityManager em, String entityJavaType,
			Map<String, Object> subEntityAttributes) {

		String query = buildReferencedObjectQuery(entityJavaType, subEntityAttributes);
		logQuery(query);

		Query tmpQuery = em.createQuery(query);
		// Getting list of records...
		final List result = tmpQuery.getResultList();

		if (result == null || result.isEmpty()) {
			throw new SpagoBIRuntimeException(
					"Record with attributes [" + subEntityAttributes + "] not found for entity " + entityJavaType);
		}
		if (result.size() > 1) {
			throw new SpagoBIRuntimeException("More than 1 record with attributes [" + subEntityAttributes
					+ "] were found in entity " + entityJavaType);
		}

		// load the object key
		Object key = result.get(0);

		int lastPkgDotSub = entityJavaType.lastIndexOf(".");
		String entityNameNoPkgSub = entityJavaType.substring(lastPkgDotSub + 1);
		Class<?> clazz = getEntityClass(em, entityNameNoPkgSub);
		// load the entire object corresponding to the key
		return em.find(clazz, key);
	}

	protected Class<?> getEntityClass(EntityManager entityManager, String entityName) {
		EntityType entityType = getEntityByName(entityManager, entityName);
		if (entityType == null) {
			throw new SpagoBIRuntimeException("Cannot find entity type for entity [" + entityName + "]");
		}
		return entityType.getJavaType();
	}

	private String buildReferencedObjectQuery(String entityJavaType, Map<String, Object> subEntityAttributes) {
		it.eng.qbe.query.Query query = buildRegularQueryToReferenceObject(entityJavaType, subEntityAttributes);
		// we create a dataset object to apply profiled visibility constraints
		JPQLDataSet dataset = buildJPQLDatasetToReferenceObject(query);
		IStatement filteredStatement = dataset.getFilteredStatement();
		return filteredStatement.getQueryString();
	}

	protected JPQLDataSet buildJPQLDatasetToReferenceObject(it.eng.qbe.query.Query query) {
		JPQLStatement statement = (JPQLStatement) getDataSource().createStatement(query);
		JPQLDataSet dataset = new JPQLDataSet(statement);
		// setting user profile attributes
		Map userAttributes = new HashMap();
		UserProfile profile = UserProfileManager.getProfile();
		Iterator it = profile.getUserAttributeNames().iterator();
		while (it.hasNext()) {
			String attributeName = (String) it.next();
			Object attributeValue;
			try {
				attributeValue = profile.getUserAttribute(attributeName);
			} catch (EMFInternalError e) {
				throw new SpagoBIRuntimeException("Error while getting user profile attribute [" + attributeName + "]",
						e);
			}
			userAttributes.put(attributeName, attributeValue);
		}
		dataset.addBinding("attributes", userAttributes);
		dataset.setUserProfileAttributes(userAttributes);
		return dataset;
	}

	protected it.eng.qbe.query.Query buildRegularQueryToReferenceObject(String entityJavaType,
			Map<String, Object> subEntityAttributes) {
		it.eng.qbe.query.Query query = new it.eng.qbe.query.Query();
		query.setId(StringUtilities.getRandomString(5));
		int lastPkgDotSub = entityJavaType.lastIndexOf(".");
		String entityNameNoPkgSub = entityJavaType.substring(lastPkgDotSub + 1);
		IModelEntity entity = dataSource.getModelStructure().getEntityByName(entityNameNoPkgSub);
		List<IModelField> fields = entity.getKeyFields();
		for (Iterator<IModelField> it = fields.iterator(); it.hasNext();) {
			IModelField field = it.next();
			query.addSelectFiled(field.getUniqueName(), "NONE", field.getName(), true, true, false, null, null);
		}
		ArrayList<ExpressionNode> expressionNodes = new ArrayList<>();
		for (Iterator iterator = subEntityAttributes.keySet().iterator(); iterator.hasNext();) {
			String key = (String) iterator.next();
			Object value = subEntityAttributes.get(key);
			if (value != null) {
				WhereField.Operand left = new WhereField.Operand(new String[] { entityJavaType + ":" + key }, "name",
						AbstractStatement.OPERAND_TYPE_SIMPLE_FIELD, null, null);
				WhereField.Operand right = new WhereField.Operand(new String[] { value.toString() }, "value",
						AbstractStatement.OPERAND_TYPE_STATIC, null, null);
				query.addWhereField(key, key, false, left, CriteriaConstants.EQUALS_TO, right, "AND");
				expressionNodes.add(new ExpressionNode("NODE_CONST", "$F{" + key + "}"));
			}
		}
		// put together expression nodes
		if (expressionNodes.size() == 1) {
			query.setWhereClauseStructure(expressionNodes.get(0));
		} else if (expressionNodes.size() > 1) {
			ExpressionNode exprNodeAnd = new ExpressionNode("NODE_OP", "AND");
			exprNodeAnd.setChildNodes(expressionNodes);
			query.setWhereClauseStructure(exprNodeAnd);
		}
		return query;
	}

	private void logQuery(String jpqlQuery) {
		UserProfile userProfile = UserProfileManager.getProfile();
		auditlogger.info("[" + userProfile.getUserId() + "]:: JPQL: " + jpqlQuery);
		logger.debug("[" + userProfile.getUserId() + "]:: JPQL: " + jpqlQuery);
	}

	@Override
	public void addDefaultValueToRecord(JSONObject aRecord, RegistryConfiguration registryConf) throws JSONException {
		Iterator it = aRecord.keys();
		while (it.hasNext()) {
			String attributeName = (String) it.next();
			logger.debug("Processing column [" + attributeName + "] ...");
			Column column = registryConf.getColumnConfiguration(attributeName);
			String columnValue = String.valueOf(aRecord.get(attributeName));
			boolean hasDefaultValue = column.getDefaultValue() != null;
			if (hasDefaultValue && (columnValue == null || (columnValue != null && columnValue.isEmpty()))) {
				aRecord.put(attributeName, column.getDefaultValue());
			}
		}
	}

}
