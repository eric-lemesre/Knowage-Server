package it.eng.spagobi.tools.alert.metadata;

import it.eng.spagobi.commons.metadata.SbiHibernateModel;

public class SbiAlertAction extends SbiHibernateModel {

	private Integer id;
	private String name;
	private String className;
//	private String template;

	public SbiAlertAction(Integer id) {
		this.id=id;
	}

	public SbiAlertAction() {
		
	}

	/**
	 * @return the id
	 */
	public Integer getId() {
		return id;
	}

	/**
	 * @param id the id to set
	 */
	private void setId(Integer id) {
		this.id = id;
	}

	/**
	 * @return the name
	 */
	public String getName() {
		return name;
	}

	/**
	 * @param name the name to set
	 */
	public void setName(String name) {
		this.name = name;
	}

	/**
	 * @return the className
	 */
	public String getClassName() {
		return className;
	}

	/**
	 * @param className the className to set
	 */
	public void setClassName(String className) {
		this.className = className;
	}
//
//	/**
//	 * @return the template
//	 */
//	public String getTemplate() {
//		return template;
//	}
//
//	/**
//	 * @param template the template to set
//	 */
//	public void setTemplate(String template) {
//		this.template = template;
//	}

}
