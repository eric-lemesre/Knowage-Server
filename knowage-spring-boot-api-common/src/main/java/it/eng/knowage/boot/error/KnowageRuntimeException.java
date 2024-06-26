/*
 * Knowage, Open Source Business Intelligence suite
 * Copyright (C) 2022 Engineering Ingegneria Informatica S.p.A.
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

package it.eng.knowage.boot.error;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.ws.rs.core.Response.Status;

import it.eng.knowage.boot.utils.EngineMessageBundle;

public class KnowageRuntimeException extends RuntimeException {

	/*
	 * Locale
	 */
	private Locale locale = Locale.US;

	/*
	 * Status
	 */
	private Status status = null;

	/*
	 * Error Code
	 */
	private String code = null;

	/*
	 * User oriented description of the exception. It is usually prompted to the user.
	 * Instead the message passed to the constructor is developer oriented and it should be just logged.
	 */
	private String description;

	/*
	 * A list of possible solutions to the problem that have caused the exception
	 */
	private final List<String> hints = new ArrayList<>();

	/**
	 * Builds a <code>SpagoBIRuntimeException</code>.
	 *
	 * @param message Text of the exception
	 */
	public KnowageRuntimeException(String message) {
		super(message);
	}

	/**
	 * Builds a <code>SpagoBIRuntimeException</code>.
	 *
	 * @param message Text of the exception
	 * @param ex previous Throwable object
	 */
	public KnowageRuntimeException(String message, Throwable ex) {
		super(message, ex);
	}

	/**
	 * Builds a <code>SpagoBIRuntimeException</code>.
	 *
	 * @param ex previous Throwable object
	 */
	public KnowageRuntimeException(Throwable ex) {
		super(ex);
	}

	public String getCode() {
		return code;
	}

	public void setCode(String code) {
		this.code = code;
	}

	public Status getStatus() {
		return status;
	}

	public void setStatus(Status status) {
		this.status = status;
	}

	public Locale getLocale() {
		return locale;
	}

	public void setLocale(Locale locale) {
		this.locale = locale;
	}


	@Override
	public String getLocalizedMessage() {
		String localizedMessage = EngineMessageBundle.getMessage(getCode(), getLocale());
		return localizedMessage;
	}

	public Throwable getRootException() {
		Throwable rootException;

		rootException = this;
		while (rootException.getCause() != null) {
			rootException = rootException.getCause();
		}

		return rootException;
	}

	public String getRootCause() {
		String rootCause;
		Throwable rootException = getRootException();

		rootCause = rootException.getMessage()!=null
			? rootException.getClass().getName() + ": " + rootException.getMessage()
			: rootException.getClass().getName();

		return rootCause;
	}

	public String getStackTraceDump() {
		StringWriter buffer = new StringWriter();
		this.printStackTrace(new PrintWriter(buffer));
		return buffer.toString();
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public final List<String> getHints() {
		return hints;
	}

	public final void addHint(String hint) {
		getHints().add(hint);
	}

}
