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
package it.eng.spagobi.analiticalmodel.document.dao;

import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import org.apache.log4j.Logger;
import org.hibernate.HibernateException;
import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.Transaction;

import it.eng.spago.error.EMFErrorSeverity;
import it.eng.spago.error.EMFInternalError;
import it.eng.spago.error.EMFUserError;
import it.eng.spago.security.IEngUserProfile;
import it.eng.spagobi.analiticalmodel.document.bo.SubObject;
import it.eng.spagobi.analiticalmodel.document.metadata.SbiObjects;
import it.eng.spagobi.analiticalmodel.document.metadata.SbiSubObjects;
import it.eng.spagobi.commons.bo.UserProfile;
import it.eng.spagobi.commons.dao.AbstractHibernateDAO;
import it.eng.spagobi.commons.dao.DAOFactory;
import it.eng.spagobi.commons.metadata.SbiBinContents;
import it.eng.spagobi.tools.objmetadata.bo.ObjMetacontent;
import it.eng.spagobi.tools.objmetadata.bo.ObjMetadata;
import it.eng.spagobi.tools.objmetadata.dao.IObjMetacontentDAO;

public class SubObjectDAOHibImpl extends AbstractHibernateDAO implements ISubObjectDAO {

	private static Logger logger = Logger.getLogger(SubObjectDAOHibImpl.class);

	/*
	 * (non-Javadoc)
	 *
	 * @see it.eng.spagobi.analiticalmodel.document.dao.ISubObjectDAO#getAccessibleSubObjects(java.lang.Integer, it.eng.spago.security.IEngUserProfile)
	 */
	@Override
	public List<SubObject> getAccessibleSubObjects(Integer idBIObj, IEngUserProfile profile) throws EMFUserError {
		List<SubObject> subs = new ArrayList<>();
		Session aSession = null;
		Transaction tx = null;
		try {
			aSession = getSession();
			tx = aSession.beginTransaction();

			String hql = "from SbiSubObjects sso where sso.sbiObject.biobjId= ? " + "and (isPublic = true or owner = ? )";

			Query query = aSession.createQuery(hql);
			query.setInteger(0, idBIObj.intValue());
			query.setString(1, ((UserProfile) profile).getUserId().toString());

			List<SbiSubObjects> result = query.list();
			Iterator<SbiSubObjects> it = result.iterator();
			while (it.hasNext()) {
				subs.add(toSubobject(it.next()));
			}
			tx.commit();
		} catch (HibernateException he) {
			rollbackIfActive(tx);
			logger.error(he);
			throw new EMFUserError(EMFErrorSeverity.ERROR, "100");
		} finally {
			closeSessionIfOpen(aSession);
		}
		return subs;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see it.eng.spagobi.analiticalmodel.document.dao.ISubObjectDAO#getPublicSubObjects(java.lang.Integer)
	 */
	@Override
	public List<SubObject> getPublicSubObjects(Integer idBIObj) throws EMFUserError {
		List<SubObject> subs = new ArrayList<>();
		Session aSession = null;
		Transaction tx = null;
		try {
			aSession = getSession();
			tx = aSession.beginTransaction();
			String hql = "from SbiSubObjects sso where sso.sbiObject.biobjId= ?" + "and isPublic = true";

			Query query = aSession.createQuery(hql);
			query.setInteger(0, idBIObj.intValue());

			List<SbiSubObjects> result = query.list();
			Iterator<SbiSubObjects> it = result.iterator();
			while (it.hasNext()) {
				subs.add(toSubobject(it.next()));
			}
			tx.commit();
		} catch (HibernateException he) {
			rollbackIfActive(tx);
			logger.error(he);
			throw new EMFUserError(EMFErrorSeverity.ERROR, "100");
		} finally {
			closeSessionIfOpen(aSession);
		}
		return subs;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see it.eng.spagobi.analiticalmodel.document.dao.ISubObjectDAO#getSubObjects(java.lang.Integer)
	 */
	@Override
	public List<SubObject> getSubObjects(Integer idBIObj) throws EMFUserError {
		List<SubObject> subs = new ArrayList<>();
		Session aSession = null;
		Transaction tx = null;
		try {
			aSession = getSession();
			tx = aSession.beginTransaction();
			String hql = "from SbiSubObjects sso where sso.sbiObject.biobjId=?";

			Query query = aSession.createQuery(hql);
			query.setInteger(0, idBIObj.intValue());

			List<SbiSubObjects> result = query.list();
			Iterator<SbiSubObjects> it = result.iterator();
			while (it.hasNext()) {
				subs.add(toSubobject(it.next()));
			}
			tx.commit();
		} catch (HibernateException he) {
			rollbackIfActive(tx);
			logger.error(he);
			throw new EMFUserError(EMFErrorSeverity.ERROR, "100");
		} finally {
			closeSessionIfOpen(aSession);
		}
		return subs;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see it.eng.spagobi.analiticalmodel.document.dao.ISubObjectDAO#deleteSubObject(java.lang.Integer)
	 */
	@Override
	public void deleteSubObject(Integer idSub) throws EMFUserError {
		Session aSession = null;
		Transaction tx = null;
		try {
			aSession = getSession();
			tx = aSession.beginTransaction();
			SbiSubObjects hibSubobject = (SbiSubObjects) aSession.load(SbiSubObjects.class, idSub);
			SbiBinContents hibBinCont = hibSubobject.getSbiBinContents();

			// delete metadata eventually associated
			List<ObjMetadata> metadata = DAOFactory.getObjMetadataDAO().loadAllObjMetadata();
			IObjMetacontentDAO objMetaContentDAO = DAOFactory.getObjMetacontentDAO();
			if (metadata != null && !metadata.isEmpty()) {
				Iterator<ObjMetadata> it = metadata.iterator();
				while (it.hasNext()) {
					ObjMetadata objMetadata = it.next();
					ObjMetacontent objMetacontent = DAOFactory.getObjMetacontentDAO().loadObjMetacontent(objMetadata.getObjMetaId(),
							hibSubobject.getSbiObject().getBiobjId(), hibSubobject.getSubObjId());
					if (objMetacontent != null) {
						objMetaContentDAO.eraseObjMetadata(objMetacontent);
					}
				}
			}

			aSession.delete(hibSubobject);
			aSession.delete(hibBinCont);
			tx.commit();
		} catch (HibernateException he) {
			rollbackIfActive(tx);
			logger.error(he);
			throw new EMFUserError(EMFErrorSeverity.ERROR, 100);
		} finally {
			closeSessionIfOpen(aSession);
		}
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see it.eng.spagobi.analiticalmodel.document.dao.ISubObjectDAO#deleteSubObject(java.lang.Integer)
	 */
	@Override
	public void deleteSubObjectSameConnection(Integer idSub, Session aSession) throws EMFUserError {
		Transaction tx = null;
		try {
			aSession = getSession();
			tx = aSession.beginTransaction();
			SbiSubObjects hibSubobject = (SbiSubObjects) aSession.load(SbiSubObjects.class, idSub);
			SbiBinContents hibBinCont = hibSubobject.getSbiBinContents();

			// delete metadata eventually associated
			List<ObjMetadata> metadata = DAOFactory.getObjMetadataDAO().loadAllObjMetadata();
			IObjMetacontentDAO objMetaContentDAO = DAOFactory.getObjMetacontentDAO();
			if (metadata != null && !metadata.isEmpty()) {
				Iterator<ObjMetadata> it = metadata.iterator();
				while (it.hasNext()) {
					ObjMetadata objMetadata = it.next();
					ObjMetacontent objMetacontent = DAOFactory.getObjMetacontentDAO().loadObjMetacontent(objMetadata.getObjMetaId(),
							hibSubobject.getSbiObject().getBiobjId(), hibSubobject.getSubObjId());
					if (objMetacontent != null) {
						objMetaContentDAO.eraseObjMetadata(objMetacontent);
					}
				}
			}

			aSession.delete(hibSubobject);
			aSession.delete(hibBinCont);
			tx.commit();
		} catch (HibernateException he) {
			rollbackIfActive(tx);
			logger.error(he);
			throw new EMFUserError(EMFErrorSeverity.ERROR, 100);
		}
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see it.eng.spagobi.analiticalmodel.document.dao.ISubObjectDAO#getSubObject(java.lang.Integer)
	 */
	@Override
	public SubObject getSubObject(Integer idSubObj) throws EMFUserError {
		SubObject sub = null;
		Session aSession = null;
		Transaction tx = null;
		try {
			aSession = getSession();
			tx = aSession.beginTransaction();
			SbiSubObjects hibSub = (SbiSubObjects) aSession.load(SbiSubObjects.class, idSubObj);
			sub = toSubobject(hibSub);
			tx.commit();
		} catch (HibernateException he) {
			rollbackIfActive(tx);
			logger.error(he);
			throw new EMFUserError(EMFErrorSeverity.ERROR, 100);
		} finally {
			closeSessionIfOpen(aSession);
		}
		return sub;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see it.eng.spagobi.analiticalmodel.document.dao.ISubObjectDAO#saveSubObject(java.lang.Integer, it.eng.spagobi.analiticalmodel.document.bo.SubObject)
	 */
	@Override
	public Integer saveSubObject(Integer idBIObj, SubObject subObj) throws EMFUserError {
		Session aSession = null;
		Transaction tx = null;
		Integer subObjId = null;
		try {
			aSession = getSession();
			tx = aSession.beginTransaction();
			SbiObjects hibBIObject = (SbiObjects) aSession.load(SbiObjects.class, idBIObj);
			SbiBinContents hibBinContent = new SbiBinContents();
			byte[] bytes = null;
			try {
				bytes = subObj.getContent();
			} catch (EMFInternalError e) {
				logger.error("Could not retrieve content of SubObject object in input.");
				throw new EMFUserError(EMFErrorSeverity.ERROR, 100);
			}
			hibBinContent.setContent(bytes);
			updateSbiCommonInfo4Insert(hibBinContent);
			Integer idBin = (Integer) aSession.save(hibBinContent);
			// recover the saved binary hibernate object
			hibBinContent = (SbiBinContents) aSession.load(SbiBinContents.class, idBin);
			// store the subobject
			Date now = new Date();
			SbiSubObjects hibSub = new SbiSubObjects();
			hibSub.setOwner(subObj.getOwner());
			hibSub.setLastChangeDate(now);
			hibSub.setIsPublic(subObj.getIsPublic());
			hibSub.setCreationDate(now);
			hibSub.setDescription(subObj.getDescription());
			hibSub.setName(subObj.getName());
			hibSub.setSbiBinContents(hibBinContent);
			hibSub.setSbiObject(hibBIObject);
			updateSbiCommonInfo4Insert(hibSub);
			aSession.save(hibSub);
			subObjId = hibSub.getSubObjId();
			tx.commit();
		} catch (HibernateException he) {
			rollbackIfActive(tx);
			logger.error(he);
			throw new EMFUserError(EMFErrorSeverity.ERROR, 100);
		} finally {
			closeSessionIfOpen(aSession);
		}
		return subObjId;
	}

	private SubObject toSubobject(SbiSubObjects hibsub) {
		SubObject subobj = new SubObject();
		subobj.setBiobjId(hibsub.getSbiObject().getBiobjId());
		subobj.setCreationDate(hibsub.getCreationDate());
		subobj.setDescription(hibsub.getDescription());
		subobj.setId(hibsub.getSubObjId());
		subobj.setIsPublic(hibsub.getIsPublic());
		subobj.setLastChangeDate(hibsub.getLastChangeDate());
		subobj.setName(hibsub.getName());
		subobj.setOwner(hibsub.getOwner());
		subobj.setContent(hibsub.getSbiBinContents().getContent());
		subobj.setBinaryContentId(hibsub.getSbiBinContents().getId());
		return subobj;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see it.eng.spagobi.analiticalmodel.document.dao.ISubObjectDAO#modifySubObject(java.lang.Integer, it.eng.spagobi.analiticalmodel.document.bo.SubObject)
	 */
	@Override
	public Integer modifySubObject(Integer idBIObj, SubObject subObj) throws EMFUserError {
		Session aSession = null;
		Transaction tx = null;
		Integer subObjId = null;
		try {
			aSession = getSession();
			tx = aSession.beginTransaction();
			SbiObjects hibBIObject = (SbiObjects) aSession.load(SbiObjects.class, idBIObj);
			SbiBinContents hibBinContent = new SbiBinContents();
			byte[] bytes = null;
			try {
				bytes = subObj.getContent();
			} catch (EMFInternalError e) {
				logger.error("Could not retrieve content of SubObject object in input.");
				throw new EMFUserError(EMFErrorSeverity.ERROR, 100);
			}
			hibBinContent.setContent(bytes);
			updateSbiCommonInfo4Insert(hibBinContent);
			Integer idBin = (Integer) aSession.save(hibBinContent);
			// recover the saved binary hibernate object
			hibBinContent = (SbiBinContents) aSession.load(SbiBinContents.class, idBin);
			// store the subobject
			Date now = new Date();
			subObjId = subObj.getId();
			SbiSubObjects hibSub = (SbiSubObjects) aSession.load(SbiSubObjects.class, subObj.getId());
			hibSub.setOwner(subObj.getOwner());
			hibSub.setLastChangeDate(now);
			hibSub.setIsPublic(subObj.getIsPublic());
			hibSub.setCreationDate(now);
			hibSub.setDescription(subObj.getDescription());
			hibSub.setName(subObj.getName());
			hibSub.setSbiBinContents(hibBinContent);
			hibSub.setSbiObject(hibBIObject);
			updateSbiCommonInfo4Update(hibSub);
			aSession.save(hibSub);

			tx.commit();
		} catch (HibernateException he) {
			rollbackIfActive(tx);
			logger.error(he);
			throw new EMFUserError(EMFErrorSeverity.ERROR, 100);
		} finally {
			closeSessionIfOpen(aSession);
		}
		return subObjId;
	}

	@Override
	public SubObject getSubObjectByNameAndBIObjectId(String subobjectName, Integer idBIObj) throws EMFUserError {
		SubObject subObject = null;
		List<SubObject> subObjects = this.getSubObjects(idBIObj);
		if (subObjects != null && !subObjects.isEmpty()) {
			Iterator<SubObject> it = subObjects.iterator();
			while (it.hasNext()) {
				SubObject temp = it.next();
				if (temp.getName().equalsIgnoreCase(subobjectName)) {
					subObject = temp;
					break;
				}
			}
		}
		return subObject;
	}

}
