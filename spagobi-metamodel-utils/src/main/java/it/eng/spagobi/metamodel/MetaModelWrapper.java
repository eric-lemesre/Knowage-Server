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

package it.eng.spagobi.metamodel;

import it.eng.spagobi.meta.model.Model;
import it.eng.spagobi.meta.model.olap.Dimension;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.common.util.EList;

/**
 * 
 * This class wraps a it.eng.spagobi.meta.model.Model
 * @author Alberto Ghedin (alberto.ghedin@eng.it)
 *
 */


public class MetaModelWrapper {

	private Model model;
	private List<HierarchyWrapper> hierarchies;
	private SiblingsFileWrapper siblingsFileWrapper;
	
	public MetaModelWrapper(Model model){
		this.model = model;
		buildHierarchies();
	}
	
	/**
	 * Build the list of hierarchies
	 */
	private void buildHierarchies(){
		hierarchies = new ArrayList<>();
		if(model!=null && model.getOlapModels()!=null && model.getOlapModels().size()>0 && model.getOlapModels().get(0)!=null && model.getOlapModels().get(0).getDimensions()!=null){
			EList<Dimension> dimension = model.getOlapModels().get(0).getDimensions();
			for(int i=0; i<dimension.size(); i++){
				EList<it.eng.spagobi.meta.model.olap.Hierarchy> modelHierarchies = dimension.get(i).getHierarchies();
				for(int j=0; j<modelHierarchies.size(); j++){
					hierarchies.add(new HierarchyWrapper(modelHierarchies.get(j)));
				}
			}
		}
	}
	
	/**
	 * Returns the list of hierarchies
	 */
	public List<HierarchyWrapper> getHierarchies() {
		return hierarchies;
	}
	
	/**
	 * 
	 * Returns a hierarchy with the passed name.
	 * If it does not exist
	 * 
	 * */
	public HierarchyWrapper getHierarchy(String HierarchyName){
		if(hierarchies!=null){
			for(int i=0; i<hierarchies.size(); i++){
				HierarchyWrapper h = hierarchies.get(i);
				if(h.getName().equals(HierarchyName)){
					return h;
				}
			}
		}
		return null;
	}

	/**
	 * @return the siblingsFileWrapper
	 */
	public SiblingsFileWrapper getSiblingsFileWrapper() {
		return siblingsFileWrapper;
	}

	/**
	 * @param siblingsFileWrapper the siblingsFileWrapper to set
	 */
	public void setSiblingsFileWrapper(SiblingsFileWrapper siblingsFileWrapper) {
		this.siblingsFileWrapper = siblingsFileWrapper;
	}
	

}
