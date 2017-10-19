/*******************************************************************************
 * DIANNE  - Framework for distributed artificial neural networks
 * Copyright (C) 2015  iMinds - IBCN - UGent
 *
 * This file is part of DIANNE.
 *
 * DIANNE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     Tim Verbelen, Steven Bohez
 *******************************************************************************/
package be.iminds.iot.dianne.nn.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;

import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;

import be.iminds.iot.dianne.api.nn.module.BackwardListener;
import be.iminds.iot.dianne.api.nn.module.Composite;
import be.iminds.iot.dianne.api.nn.module.ForwardListener;
import be.iminds.iot.dianne.api.nn.module.Input;
import be.iminds.iot.dianne.api.nn.module.Memory;
import be.iminds.iot.dianne.api.nn.module.Module;
import be.iminds.iot.dianne.api.nn.module.Output;
import be.iminds.iot.dianne.api.nn.module.Preprocessor;
import be.iminds.iot.dianne.api.nn.module.Trainable;
import be.iminds.iot.dianne.api.nn.module.dto.ModuleDTO;
import be.iminds.iot.dianne.api.nn.module.dto.ModuleInstanceDTO;
import be.iminds.iot.dianne.api.nn.module.dto.ModuleTypeDTO;
import be.iminds.iot.dianne.api.nn.module.factory.ModuleFactory;
import be.iminds.iot.dianne.api.nn.runtime.DianneRuntime;
import be.iminds.iot.dianne.api.repository.DianneRepository;
import be.iminds.iot.dianne.tensor.Tensor;

@Component(immediate=true, 
	property={"service.pid=be.iminds.iot.dianne.nn.module",
			  "aiolos.unique=true"})
public class DianneRuntimeImpl implements DianneRuntime {

	private BundleContext context;
	private UUID runtimeId;
	private String name;
	
	private DianneRepository repository;
	
	private List<ModuleFactory> moduleFactories = Collections.synchronizedList(new ArrayList<ModuleFactory>());
	
	// All known modules
	private ModuleMap<Module> modules = new ModuleMap<Module>();
	// All module service registrations 
	private ModuleMap<ServiceRegistration<?>> registrations = new ModuleMap<>();
	private ModuleMap<ModuleInstanceDTO> instances = new ModuleMap<ModuleInstanceDTO>();
	
	private Map<UUID, List<UUID>> nextMap = new HashMap<UUID, List<UUID>>();
	private Map<UUID, List<UUID>> prevMap = new HashMap<UUID, List<UUID>>();
	
	// Listener targets are defined as nnId:moduleId
	private Map<ForwardListener, List<String>> forwardListeners = new HashMap<ForwardListener, List<String>>();
	private Map<BackwardListener, List<String>> backwardListeners = new HashMap<BackwardListener, List<String>>();

	// Blacklisted module uuids that should not be deployed on this runtime
	List<UUID> blacklist = new ArrayList<>();
	
	@Activate
	public void activate(BundleContext context){
		this.context = context;
		this.runtimeId = UUID.fromString(context.getProperty(Constants.FRAMEWORK_UUID));
		this.name = context.getProperty("be.iminds.iot.dianne.runtime.name");
		if(name==null){
			name = runtimeId.toString().substring(0, runtimeId.toString().indexOf('-'));
		}
		
		String blacklistString = context.getProperty("be.iminds.iot.dianne.runtime.blacklist");
		if(blacklistString!=null){
			String[] blacklistModules = blacklistString.split(",");
			for(String b : blacklistModules){
				try {
					blacklist.add(UUID.fromString(b));
				} catch(Exception e){
					System.err.println("Error blacklisting moduleId "+b);
				}
			}
		}
	}
	
	@Deactivate
	public void deactivate(){
		synchronized(registrations){
			for(ServiceRegistration<?> reg : registrations.values()){
				reg.unregister();
			}
		}
	}

	@Reference(cardinality = ReferenceCardinality.OPTIONAL, 
			policy = ReferencePolicy.DYNAMIC)
	void setDianneRepository(DianneRepository repo) {
		this.repository = repo;
	}

	void unsetDianneRepository(DianneRepository repo) {
		if (this.repository == repo)
			this.repository = null;
	}
	
	@Reference(cardinality=ReferenceCardinality.AT_LEAST_ONE, 
			policy=ReferencePolicy.DYNAMIC)
	void addModuleFactory(ModuleFactory factory){
		this.moduleFactories.add(factory);
	}
	
	void removeModuleFactory(ModuleFactory factory){
		this.moduleFactories.remove(factory);
	}
	
	@Reference(
			cardinality=ReferenceCardinality.MULTIPLE, 
			policy=ReferencePolicy.DYNAMIC)
	void addModule(Module module, Map<String, Object> properties){
		UUID moduleId = UUID.fromString((String)properties.get("module.id"));
		UUID nnId = UUID.fromString((String)properties.get("nn.id"));

		modules.put(moduleId, nnId, module);
		
		// configure local modules that require this module
		for(Module m : findDependingModules(moduleId, nnId, nextMap)){
			configureNext(m, nnId);
		}
		for(Module m : findDependingModules(moduleId, nnId, prevMap)){
			configurePrevious(m, nnId);
		}
		
		configureModuleListeners(moduleId, nnId, module);
		
	}
	
	synchronized void updatedModule(Module module, Map<String, Object> properties){
		UUID moduleId = UUID.fromString((String)properties.get("module.id"));
		UUID nnId = UUID.fromString((String)properties.get("nn.id"));

		configureModuleListeners(moduleId, nnId, module);
	}
	
	private void configureModuleListeners(UUID moduleId, UUID nnId, Module module){
		// check if someone is listening for this module

		synchronized(forwardListeners){
			Iterator<Entry<ForwardListener, List<String>>> it = forwardListeners.entrySet().iterator();
			while(it.hasNext()){
				Entry<ForwardListener, List<String>> e = it.next();
				for(String target : e.getValue()){
					configureForwardListener(e.getKey(), moduleId, nnId, module, target);
				}
			}
		}
		
		synchronized(backwardListeners){
			Iterator<Entry<BackwardListener, List<String>>> it = backwardListeners.entrySet().iterator();
			while(it.hasNext()){
				Entry<BackwardListener, List<String>> e = it.next();
				for(String target : e.getValue()){
					configureBackwardListener(e.getKey(), moduleId, nnId, module, target);
				}
			}
		}
	}
	
	private void configureForwardListener(ForwardListener l, UUID moduleId, UUID nnId, Module module, String target){
		if(!registrations.containsKey(moduleId, nnId)){
			return;
		}
		
		String[] split = target.split(":");
		if(split.length==1){
			// only nnId
			UUID nid = UUID.fromString(split[0]);
			if(nid.equals(nnId)){
				// only add to output modules 
				if(module instanceof Output){
					module.addForwardListener(l);
				}
			}
		} else {
			if(split[0].length()==0){
				// only moduleId
				UUID mid = UUID.fromString(split[0]);
				if(mid.equals(moduleId)){
					module.addForwardListener(l);
				}
			} else {
				// nnId:moduleId
				UUID nid = UUID.fromString(split[0]);
				UUID mid = UUID.fromString(split[1]);
				if(nid.equals(nnId) && mid.equals(moduleId)){
					module.addForwardListener(l);
				}
			}
		}
	}
	
	private void configureBackwardListener(BackwardListener l, UUID moduleId, UUID nnId, Module module, String target){
		if(!registrations.containsKey(moduleId, nnId)){
			return;
		}
		
		String[] split = target.split(":");
		if(split.length==1){
			// only nnId
			UUID nid = UUID.fromString(split[0]);
			if(nid.equals(nnId)){
				// only add to input modules 
				if(module instanceof Input){
					module.addBackwardListener(l);
				}
			}
		} else {
			if(split[0].length()==0){
				// only moduleId
				UUID mid = UUID.fromString(split[0]);
				if(mid.equals(moduleId)){
					module.addBackwardListener(l);
				}
			} else {
				// nnId:moduleId
				UUID nid = UUID.fromString(split[0]);
				UUID mid = UUID.fromString(split[1]);
				if(nid.equals(nnId) && mid.equals(moduleId)){
					module.addBackwardListener(l);
				}
			}
		}
	}
	
	void removeModule(Module module, Map<String, Object> properties){
		UUID moduleId = UUID.fromString((String)properties.get("module.id"));
		UUID nnId = UUID.fromString((String)properties.get("nn.id"));
		
		modules.remove(moduleId, nnId);

		// unconfigure modules that require this module
		for(Module m : findDependingModules(moduleId, nnId, nextMap)){
			unconfigureNext(m);
		}
		for(Module m : findDependingModules(moduleId, nnId, prevMap)){
			unconfigurePrevious(m);
		}
	}
	
	@Reference(
			cardinality=ReferenceCardinality.MULTIPLE, 
			policy=ReferencePolicy.DYNAMIC)
	void addForwardListener(ForwardListener l, Map<String, Object> properties){
		String[] targets = (String[])properties.get("targets");
		if(targets!=null){
			for(String target : targets){
				// TODO filter out the modules that should match this target 
				// instead of iterating all and trying?
				synchronized(modules){
					Iterator<ModuleMap<Module>.Entry<Module>> it = modules.iterator();
					while(it.hasNext()){
						ModuleMap<Module>.Entry<Module> e = it.next();
						configureForwardListener(l, e.moduleId, e.nnId, e.value, target);
					}
				}
			}
			forwardListeners.put(l, Arrays.asList(targets));
		}
	}
	
	void removeForwardListener(ForwardListener l){
		forwardListeners.remove(l);
		// TODO filter out the modules that actually have this listener registered?
		synchronized(instances){
			for(ModuleInstanceDTO mi : instances.values()){
				Module m = modules.get(mi.moduleId, mi.nnId);
				if(m!=null)
					m.removeForwardListener(l);

			}
		}
	}
	
	
	@Reference(
			cardinality=ReferenceCardinality.MULTIPLE, 
			policy=ReferencePolicy.DYNAMIC)
	void addBackwardListener(BackwardListener l, Map<String, Object> properties){
		String[] targets = (String[])properties.get("targets");
		if(targets!=null){
			for(String target : targets){
				// TODO filter out the modules that should match this target 
				// instead of iterating all and trying?
				synchronized(modules){
					Iterator<ModuleMap<Module>.Entry<Module>> it = modules.iterator();
					while(it.hasNext()){
						ModuleMap<Module>.Entry<Module> e = it.next();
						configureBackwardListener(l, e.moduleId, e.nnId, e.value, target);
					}
				}
			}
			backwardListeners.put(l, Arrays.asList(targets));
		}
	}
	
	void removeBackwardListener(BackwardListener l){
		backwardListeners.remove(l);
		// TODO filter out the modules that actually have this listener registered?
		synchronized(instances){
			for(ModuleInstanceDTO mi : instances.values()){
				Module m = modules.get(mi.moduleId, mi.nnId);
				if(m!=null)
					m.removeBackwardListener(l);

			}
		}
	}
	
	@Override
	public ModuleInstanceDTO deployModule(ModuleDTO dto, UUID nnId, Tensor parameters){
		if(blacklist.contains(dto.id)){
			throw new RuntimeException("Module "+dto.id+" cannot be deployed on runtime "+name);
		}
		
		// Create and register module
		Module module = null;
		synchronized(moduleFactories){
			Iterator<ModuleFactory> it = moduleFactories.iterator();
			while(module == null && it.hasNext()){
				try {
					ModuleFactory mFactory = it.next();
					module = mFactory.createModule(dto, parameters);
				} catch(InstantiationException e){
					// means this factory cannot create this module type ... ignore
				} catch(Exception ex){
					ex.printStackTrace();
					// something seriously went wrong
					// out of memory?
					throw new RuntimeException("Failed to instantiate module "+dto.type+" "+dto.id+" - "+ex.getClass().getName()+": "+ex.getMessage());
				}
			}
		}

		if(module==null){
			throw new RuntimeException("Failed to instantiate module "+dto.type+" "+dto.id);
		}
		
		// configure next/prev
		List<UUID> nextIDs = new ArrayList<>();
		if(dto.next!=null){
			for(UUID id : dto.next){
				nextIDs.add(id);
			}
		}
		nextMap.put(module.getId(), nextIDs);
		configureNext(module, nnId);
		
		
		List<UUID> prevIDs = new ArrayList<>();
		if(dto.prev!=null){
			for(UUID id : dto.prev){
				prevIDs.add(id);
			}
		}
		prevMap.put(module.getId(), prevIDs);
		configurePrevious(module, nnId);

		// set labels in case of output
		if(module instanceof Output){
			String labels = dto.properties.get("labels");
			if(labels!=null){
				String[] l = parseStrings(labels);
				((Output)module).setOutputLabels(l);
			}
		}

		
		UUID moduleId = module.getId();
		Dictionary<String, Object> props = new Hashtable<String, Object>();
		props.put("module.id", moduleId.toString());
		props.put("module.type", dto.type);
		props.put("nn.id", nnId.toString());
		
		
		String[] classes;
		if(module instanceof Input){
			classes = new String[]{Module.class.getName(),Input.class.getName()};
		} else if(module instanceof Output){
			classes = new String[]{Module.class.getName(),Output.class.getName()};
		} else if(module instanceof Composite){
			classes = new String[]{Module.class.getName(),Trainable.class.getName(),Memory.class.getName(),Composite.class.getName()};
			props.put("composite.nn.id", ((Composite)module).getNNi().id.toString());
		}else if(module instanceof Trainable){
			classes = new String[]{Module.class.getName(),Trainable.class.getName()};
		} else if(module instanceof Preprocessor){
			classes = new String[]{Module.class.getName(),Preprocessor.class.getName()};
		} else if(module instanceof Memory){
			classes = new String[]{Module.class.getName(),Memory.class.getName()};
		} else {
			classes = new String[]{Module.class.getName()};
		}
		
		
		// make sure that for each module all interfaces are behind a single proxy 
		// and that each module is uniquely proxied
		props.put("aiolos.combine", "*");
		props.put("aiolos.instance.id", nnId.toString()+":"+module.getId().toString());
		
		// allready add a null registration, in order to allow registrations.contains()
		// to return true in the addModule call of this class
		this.registrations.put(moduleId, nnId, null);
		ServiceRegistration<?> reg = context.registerService(classes, module, props);
		this.registrations.put(moduleId, nnId, reg);
		
		ModuleInstanceDTO instance =  new ModuleInstanceDTO(dto, nnId, runtimeId);
		this.instances.put(moduleId, nnId, instance);

		return instance;
	}

	@Override
	public ModuleInstanceDTO deployModule(ModuleDTO dto, UUID nnId, String... tags){
		if(blacklist.contains(dto.id)){
			throw new RuntimeException("Module "+dto.id+" cannot be deployed on runtime "+name);
		}
		
		Tensor parameters = getParameters(dto.id, tags);
		return deployModule(dto, nnId, parameters);
	}

	
	@Override
	public void undeployModule(ModuleInstanceDTO dto) {
		if(!dto.runtimeId.equals(runtimeId)){
			System.out.println("Can only undeploy module instances that are deployed here...");
			return;
		}
		
		if(instances.remove(dto.moduleId, dto.nnId) == null){
			System.out.println("Invalid ModuleInstanceDTO?! "+dto.nnId+":"+dto.moduleId);
			return;
		}
		
		ServiceRegistration<?> reg = registrations.remove(dto.moduleId, dto.nnId);
		if(reg!=null){
			// check if this is a composite ... if so, also undeploy composing modules
			// TODO only works if these modules are not migrated in the mean time?
			String compositeNNi = (String)reg.getReference().getProperty("composite.nn.id");

			try {
				reg.unregister();
			} catch(IllegalStateException e){
				// happens when the service was registered on behalf of the (config) bundle
				// that is uninstalled (then service is allready unregistered)
				e.printStackTrace();
			}
			
			if(compositeNNi!=null){
				undeployModules(UUID.fromString(compositeNNi));
			}
		}
		
		if(!registrations.containsKey(dto.moduleId)){
			nextMap.remove(dto.moduleId);
			prevMap.remove(dto.moduleId);
		}
	}
	
	@Override
	public void undeployModules(UUID nnId) {
		List<ModuleInstanceDTO> toRemove = new ArrayList<ModuleInstanceDTO>();
		synchronized(instances){
			Iterator<ModuleMap<ModuleInstanceDTO>.Entry<ModuleInstanceDTO>> it = instances.iterator();
			while(it.hasNext()){
				ModuleMap<ModuleInstanceDTO>.Entry<ModuleInstanceDTO> e = it.next();
				if(e.nnId.equals(nnId)){
					toRemove.add(e.value);
				}
			}
		}
		
		for(ModuleInstanceDTO m : toRemove){
			undeployModule(m);
		}
	}
	
	@Override
	public List<ModuleInstanceDTO> getModules(){
		List<ModuleInstanceDTO> modules = new ArrayList<ModuleInstanceDTO>();
		modules.addAll(instances.values());
		return modules;
	}

	@Override
	public Tensor getModuleParameters(ModuleInstanceDTO module){
		Tensor t = null;
		Module m = modules.get(module.moduleId, module.nnId);
		if(m != null){
			if(m instanceof Trainable){
				t = ((Trainable)m).getParameters();
			} else if(m instanceof Preprocessor){
				t = ((Preprocessor)m).getParameters();
			}
		}
		return t;
	}
	
	@Override
	public void loadModuleParameters(ModuleInstanceDTO module, String... tags){
		Tensor parameters = getParameters(module.module.id, tags);
		if(parameters == null)
			return;
		
		Module m = modules.get(module.moduleId, module.nnId);
		if(m != null){
			if(m instanceof Trainable){
				((Trainable)m).setParameters(parameters);
			} else if(m instanceof Preprocessor){
				((Preprocessor)m).setParameters(parameters);
			}
		}
	}
	
	@Override
	public List<ModuleTypeDTO> getSupportedModules() {
		List<ModuleTypeDTO> supported = new ArrayList<ModuleTypeDTO>();
		synchronized(moduleFactories){
			for(ModuleFactory f : moduleFactories){
				supported.addAll(f.getAvailableModuleTypes());
			}
		}
		return Collections.unmodifiableList(supported);
	}
	
	@Override
	public UUID getRuntimeId() {
		return runtimeId;
	}

	@Override
	public String getRuntimeName() {
		return name;
	}
	
	private void configureNext(Module m, UUID nnId){
		List<UUID> nextIDs = nextMap.get(m.getId());
		if(nextIDs.size()==0){
			// output module
			return;
		}
		Module[] nextModules = new Module[nextIDs.size()];
		
		int i = 0;
		for(UUID nextID : nextIDs){
			Module nextModule = modules.get(nextID, nnId);
			// TODO also allow only partly deployed NNs?
			if(nextModule== null)
				return;
			
			nextModules[i] = nextModule;
			i++;
		}
		
		m.setNext(nextModules);
	}
	
	private void unconfigureNext(Module m){
		m.setNext((Module[]) null);
	}
	
	private void configurePrevious(Module m, UUID nnId){
		List<UUID> prevIDs = prevMap.get(m.getId());
		if(prevIDs.size()==0){
			// input module
			return;
		}
		Module[] prevModules = new Module[prevIDs.size()];
		
		int i = 0;
		for(UUID prevID : prevIDs){
			Module prevModule = modules.get(prevID, nnId);
			if(prevModule== null)
				return;
			
			prevModules[i] = prevModule;
			i++;
		}
		
		m.setPrevious(prevModules);
	}
	
	private void unconfigurePrevious(Module m){
		m.setPrevious((Module[])null);
	}
	
	private List<Module> findDependingModules(UUID moduleId, UUID nnId, Map<UUID, List<UUID>> map){
		List<Module> result = new ArrayList<Module>();
		for(Iterator<Entry<UUID, List<UUID>>> it = map.entrySet().iterator();it.hasNext();){
			Entry<UUID, List<UUID>> entry = it.next();
			for(UUID nxtId : entry.getValue()){
				if(nxtId.equals(moduleId)){
					// only find locally registered modules
					if(registrations.containsKey(entry.getKey(), nnId)){
						Module m = modules.get(entry.getKey(), nnId);
						if(m!=null) // could be null if removed by external bundle stop
							result.add(m);
					} 
				}
			}
		}
		return result;
	}
	
	private Tensor getParameters(UUID moduleId, String... tags){
		Tensor parameters = null;
		if(repository != null){
			// TODO should we check first whether this module actually has parameters?
			try {
				parameters = repository.loadParameters(moduleId, tags);
			} catch(Exception e){
				// ignore
				//System.out.println("Failed to load parameters for module "+dto.id+" with tags "+Arrays.toString(tags));
			}
		}
		return parameters;
	}
	
	private String[] parseStrings(String string){
		String[] strings = string.replace("[", "").replace("]", "").split(", ");
		return strings;
	}

}
