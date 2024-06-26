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
package it.eng.spagobi.engines.commonj.runtime;

import java.io.File;
import java.util.Iterator;
import java.util.Map;

import javax.servlet.http.HttpSession;

import org.apache.log4j.Logger;

import it.eng.spagobi.commons.constants.SpagoBIConstants;
import it.eng.spagobi.engines.commonj.exception.WorkExecutionException;
import it.eng.spagobi.engines.commonj.exception.WorkNotFoundException;
import it.eng.spagobi.engines.commonj.process.CmdExecWork;
import it.eng.spagobi.engines.commonj.process.SpagoBIWork;
import it.eng.spagobi.engines.commonj.utils.ProcessesStatusContainer;
import it.eng.spagobi.services.proxy.EventServiceProxy;
import it.eng.spagobi.utilities.DynamicClassLoader;
import it.eng.spagobi.utilities.engines.AuditServiceProxy;
import it.eng.spagobi.utilities.engines.EngineConstants;
import it.eng.spagobi.utilities.threadmanager.WorkManager;


/**
 * configurazione ....
 * @author bernabei
 *
 */
public class WorkConfiguration {

	private static final Logger LOGGER = Logger.getLogger(WorkConfiguration.class);

	public static final String DEFAULT_CONTEXT = "Default";

	private WorksRepository worksRepository;

	public WorkConfiguration(WorksRepository worksRepository) {
		this.worksRepository = worksRepository;
	}


	/** This function prepare the execution of the new Process,
	 * builds Listener and work manager
	 *  Loads work class
	 * Builds WorkCOntainer and adds it to Singleton class, from where will be retrieved by startWorkAction
	 *
	 * @param session
	 * @param work
	 * @param parameters
	 * @throws WorkNotFoundException
	 * @throws WorkExecutionException
	 */

	public void configure(HttpSession session, CommonjWork work, Map parameters, String documentUnique, boolean isLabel)  throws WorkNotFoundException, WorkExecutionException {

		LOGGER.debug("IN");

		File executableWorkDir;
		ProcessesStatusContainer processesStatusContainer = ProcessesStatusContainer.getInstance();

		try {
			LOGGER.debug("Starting configure method of work : " +
					"name = [" + work.getWorkName() + "] ; " +
					"to start class= [" + work.getClassName() + "] ; ");


			executableWorkDir = worksRepository.getExecutableWorkDir(work);

			if (!worksRepository.containsWork(work)) {
				LOGGER.error("work [" +
						worksRepository.getRootDir().getPath()+"/"+work.getWorkName()+ "] not found in repository");
				throw new WorkNotFoundException("work [" +
						worksRepository.getRootDir().getPath()+"/"+work.getWorkName()+ "] not found in repository");
			}

			LOGGER.debug("Work [" + work.getWorkName() +"] succesfully found in repository");

			// load in memory all jars found in folder!
			loadJars(work, executableWorkDir);

			String classToLoad=work.getClassName();

			WorkManager wm = new WorkManager();
			LOGGER.debug("work manager instanziated");

			AuditServiceProxy auditServiceProxy=null;
			Object auditO=parameters.get(EngineConstants.ENV_AUDIT_SERVICE_PROXY);
			if(auditO!=null) auditServiceProxy=(AuditServiceProxy)auditO;
			Object eventO=parameters.get(EngineConstants.ENV_EVENT_SERVICE_PROXY);
			EventServiceProxy eventServiceProxy=null;
			eventServiceProxy=(EventServiceProxy)eventO;

			Object executionRoleO=parameters.get(SpagoBIConstants.EXECUTION_ROLE);
			String executionRole=executionRoleO!=null ? executionRoleO.toString() : "";


			// check if it is already in sessione means it is already running!!

			CommonjWorkContainer container=new CommonjWorkContainer();

			CommonjWorkListener listener = new CommonjWorkListener(auditServiceProxy, eventServiceProxy);

			if (documentUnique != null && isLabel) {
				listener.setBiObjectLabel(documentUnique);
			} else if (documentUnique != null && !isLabel) {
				listener.setBiObjectID(documentUnique);

			}

			listener.setExecutionRole(executionRole);
			listener.setWorkName(work.getWorkName());
			listener.setWorkClass(work.getClassName());
			LOGGER.info("Class to run " + classToLoad);

			LOGGER.debug("listener ready");

			Class clazz = null;
			try {
				clazz = Thread.currentThread().getContextClassLoader().loadClass(classToLoad);
			} catch (ClassNotFoundException e) {
				LOGGER.debug("class loaded not foud...", e);
			}
			Object obj = clazz.newInstance();
			LOGGER.debug("class loaded " + classToLoad);
			SpagoBIWork workToLaunch = null;
			// class loaded could be instance of CmdExecWork o di Work, testa se è il primo, se no è l'altra
			if (obj instanceof CmdExecWork) {
				LOGGER.debug("Class specified extends CmdExecWork");
				workToLaunch = (CmdExecWork) obj;
				workToLaunch.setPid(work.getPId());
				((CmdExecWork) obj).setCommand(work.getCommand());
				((CmdExecWork) obj).setCommandEnvironment(work.getCommandEnvironment());
				((CmdExecWork) obj).setCmdParameters(work.getCmdParameters());
				((CmdExecWork) obj).setClasspathParameters(work.getClasspathParameters());
				workToLaunch.setAnalyticalParameters(work.getAnalyticalParameters());
				workToLaunch.setSbiParameters(work.getSbiParametersMap());
				if (isLabel)
					workToLaunch.setSbiLabel(documentUnique);
			} else if (obj instanceof SpagoBIWork) {
				LOGGER.debug("Class specified extends Work");
				workToLaunch = (SpagoBIWork) obj;
					workToLaunch.setPid(work.getPId());
				workToLaunch.setSbiParameters(work.getSbiParametersMap());
					workToLaunch.setAnalyticalParameters(work.getAnalyticalParameters());
					if(isLabel) workToLaunch.setSbiLabel(documentUnique);
				}
				else {
					LOGGER.error("Class you want to launch should extend SpagoBIWork or CmdExecWork");
					return;
				}

				container.setPid(work.getPId());
				container.setWork(workToLaunch);
				container.setListener(listener);
				container.setName(work.getWorkName());
				container.setWm(wm);
				processesStatusContainer.getPidContainerMap().put(work.getPId(), container);

				for (Iterator iterator = processesStatusContainer.getPidContainerMap().keySet().iterator(); iterator.hasNext();) {
					String id = (String) iterator.next();
					LOGGER.debug("ID: " + id);

				}

		} catch (Throwable e) {
			LOGGER.error("An error occurred while starting up execution for work [" + work.getWorkName() + "]");
			throw new WorkExecutionException("An error occurred while starting up execution for work [" + work.getWorkName() + "]", e);
		}
	}



	/** Explores all jar files in directory workDir  to load them
	 *  TODO: extend to other types!
	 * @param work
	 * @param parameters
	 * @param workDir
	 */

	private void loadJars(CommonjWork work, File workDir) {
		LOGGER.debug("IN");

		// pass all the .jar into the folder
		File[] files = workDir.listFiles();

		for (int i = 0; i < files.length; i++) {
			File file=files[i];
			String name=file.getName();
			String ext = name.substring(name.lastIndexOf('.')+1, name.length());
			if(ext.equalsIgnoreCase("jar")){
				//updateCurrentClassLoader(file);
				LOGGER.debug("loading file "+file.getName());
				ClassLoader previous = Thread.currentThread().getContextClassLoader();
				DynamicClassLoader dcl = new DynamicClassLoader(file, previous);
				Thread.currentThread().setContextClassLoader(dcl);

			}
		}

		LOGGER.debug("OUT");
	}



}
