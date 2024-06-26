/*
 * Knowage, Open Source Business Intelligence suite
 * Copyright (C) 2021 Engineering Ingegneria Informatica S.p.A.
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
package it.eng.spagobi.services.security;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.logging.Logger;

import javax.xml.namespace.QName;
import javax.xml.ws.Service;
import javax.xml.ws.WebEndpoint;
import javax.xml.ws.WebServiceClient;
import javax.xml.ws.WebServiceFeature;

/**
 * This class was generated by the JAX-WS RI. JAX-WS RI 2.1.7-b01- Generated source version: 2.1
 *
 */
@WebServiceClient(name = "SecurityService", targetNamespace = "http://security.services.spagobi.eng.it/", wsdlLocation = "file:/C:/progetti/knowagesecurityservice/src/wsdl/SecurityService.wsdl")
public class SecurityService extends Service {

	private static final URL SECURITYSERVICE_WSDL_LOCATION;
	private static final Logger logger = Logger.getLogger(it.eng.spagobi.services.security.SecurityService.class.getName());

	static {
		URL url = null;
		try {
			URL baseUrl;
			baseUrl = it.eng.spagobi.services.security.SecurityService.class.getResource(".");
			url = new URL(baseUrl, "file:/C:/progetti/knowagesecurityservice/src/wsdl/SecurityService.wsdl");
		} catch (MalformedURLException e) {
			logger.warning(
					"Failed to create URL for the wsdl Location: 'file:/C:/progetti/knowagesecurityservice/src/wsdl/SecurityService.wsdl', retrying as a local file");
			logger.warning(e.getMessage());
		}
		SECURITYSERVICE_WSDL_LOCATION = url;
	}

	public SecurityService(URL wsdlLocation, QName serviceName) {
		super(wsdlLocation, serviceName);
	}

	public SecurityService() {
		super(SECURITYSERVICE_WSDL_LOCATION, new QName("http://security.services.spagobi.eng.it/", "SecurityService"));
	}

	/**
	 * 
	 * @return returns SecurityServiceService
	 */
	@WebEndpoint(name = "SecurityServicePort")
	public SecurityServiceService getSecurityServicePort() {
		return super.getPort(new QName("http://security.services.spagobi.eng.it/", "SecurityServicePort"), SecurityServiceService.class);
	}

	/**
	 * 
	 * @param features A list of {@link javax.xml.ws.WebServiceFeature} to configure on the proxy. Supported features not in the <code>features</code> parameter
	 *                 will have their default values.
	 * @return returns SecurityServiceService
	 */
	@WebEndpoint(name = "SecurityServicePort")
	public SecurityServiceService getSecurityServicePort(WebServiceFeature... features) {
		return super.getPort(new QName("http://security.services.spagobi.eng.it/", "SecurityServicePort"), SecurityServiceService.class, features);
	}

}
