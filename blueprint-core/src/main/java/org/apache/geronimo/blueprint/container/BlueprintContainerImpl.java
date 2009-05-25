/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.geronimo.blueprint.container;

import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Arrays;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.geronimo.blueprint.BeanProcessor;
import org.apache.geronimo.blueprint.BlueprintConstants;
import org.apache.geronimo.blueprint.ComponentDefinitionRegistryProcessor;
import org.apache.geronimo.blueprint.Destroyable;
import org.apache.geronimo.blueprint.ExtendedBeanMetadata;
import org.apache.geronimo.blueprint.ExtendedBlueprintContainer;
import org.apache.geronimo.blueprint.NamespaceHandlerRegistry;
import org.apache.geronimo.blueprint.di.DefaultExecutionContext;
import org.apache.geronimo.blueprint.di.DefaultRepository;
import org.apache.geronimo.blueprint.di.ExecutionContext;
import org.apache.geronimo.blueprint.di.Recipe;
import org.apache.geronimo.blueprint.di.IdRefRecipe;
import org.apache.geronimo.blueprint.di.RefRecipe;
import org.apache.geronimo.blueprint.di.Repository;
import org.apache.geronimo.blueprint.namespace.ComponentDefinitionRegistryImpl;
import org.apache.geronimo.blueprint.namespace.NamespaceHandlerRegistryImpl;
import org.apache.geronimo.blueprint.utils.HeaderParser;
import org.apache.geronimo.blueprint.utils.HeaderParser.PathElement;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.blueprint.container.BlueprintContainer;
import org.osgi.service.blueprint.container.ComponentDefinitionException;
import org.osgi.service.blueprint.container.Converter;
import org.osgi.service.blueprint.container.NoSuchComponentException;
import org.osgi.service.blueprint.container.BlueprintListener;
import org.osgi.service.blueprint.container.BlueprintEvent;
import org.osgi.service.blueprint.namespace.NamespaceHandler;
import org.osgi.service.blueprint.reflect.BeanArgument;
import org.osgi.service.blueprint.reflect.BeanMetadata;
import org.osgi.service.blueprint.reflect.BeanProperty;
import org.osgi.service.blueprint.reflect.CollectionMetadata;
import org.osgi.service.blueprint.reflect.ComponentMetadata;
import org.osgi.service.blueprint.reflect.Listener;
import org.osgi.service.blueprint.reflect.MapEntry;
import org.osgi.service.blueprint.reflect.MapMetadata;
import org.osgi.service.blueprint.reflect.Metadata;
import org.osgi.service.blueprint.reflect.PropsMetadata;
import org.osgi.service.blueprint.reflect.RefCollectionMetadata;
import org.osgi.service.blueprint.reflect.RefMetadata;
import org.osgi.service.blueprint.reflect.RegistrationListener;
import org.osgi.service.blueprint.reflect.ServiceMetadata;
import org.osgi.service.blueprint.reflect.ServiceReferenceMetadata;
import org.osgi.service.blueprint.reflect.Target;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TODO: javadoc
 *
 * @author <a href="mailto:dev@geronimo.apache.org">Apache Geronimo Project</a>
 * @version $Rev: 760378 $, $Date: 2009-03-31 11:31:38 +0200 (Tue, 31 Mar 2009) $
 */
public class BlueprintContainerImpl implements ExtendedBlueprintContainer, NamespaceHandlerRegistry.Listener, Runnable, SatisfiableRecipe.SatisfactionListener {

    public static final boolean BEHAVIOR_TCK_INJECTION = true;

    private static final Logger LOGGER = LoggerFactory.getLogger(BlueprintContainerImpl.class);

    private enum State {
        Unknown,
        WaitForNamespaceHandlers,
        Populated,
        WaitForInitialReferences,
        InitialReferencesSatisfied,
        WaitForInitialReferences2,
        Create,
        Created,
        Failed,
    }

    private final BundleContext bundleContext;
    private final Bundle extenderBundle;
    private final BlueprintListener eventDispatcher;
    private final NamespaceHandlerRegistry handlers;
    private final List<URL> urls;
    private final ComponentDefinitionRegistryImpl componentDefinitionRegistry;
    private final AggregateConverter converter;
    private final ScheduledExecutorService executors;
    private Set<URI> namespaces;
    private State state = State.Unknown;
    private boolean destroyed;
    private Parser parser;
    private BlueprintObjectInstantiator instantiator;
    private ServiceRegistration registration;
    private List<BeanProcessor> beanProcessors;
    private Map<String, Destroyable> destroyables = new HashMap<String, Destroyable>();
    private Map<String, List<SatisfiableRecipe>> satisfiables;
    private long timeout = 5 * 60 * 1000;
    private boolean waitForDependencies = true;
    private boolean xmlValidation = true;
    private ScheduledFuture timeoutFuture;
    private final AtomicBoolean scheduled = new AtomicBoolean();

    public BlueprintContainerImpl(BundleContext bundleContext, Bundle extenderBundle, BlueprintListener eventDispatcher, NamespaceHandlerRegistry handlers, ScheduledExecutorService executors, List<URL> urls) {
        this.bundleContext = bundleContext;
        this.extenderBundle = extenderBundle;
        this.eventDispatcher = eventDispatcher;
        this.handlers = handlers;
        this.urls = urls;
        this.converter = new AggregateConverter(this);
        this.componentDefinitionRegistry = new ComponentDefinitionRegistryImpl();
        this.executors = executors;
        this.beanProcessors = new ArrayList<BeanProcessor>();
    }

    public Bundle getExtenderBundle() {
        return extenderBundle;
    }

    public Class loadClass(String name) throws ClassNotFoundException {
        return bundleContext.getBundle().loadClass(name);
    }

    public void addDestroyable(String name, Destroyable destroyable) {
        destroyables.put(name, destroyable);
    }

    public List<BeanProcessor> getBeanProcessors() {
        return beanProcessors;
    }

    public BlueprintListener getEventDispatcher() {
        return eventDispatcher;
    }

    private void checkDirectives() {
        Bundle bundle = bundleContext.getBundle();
        Dictionary headers = bundle.getHeaders();
        String symbolicName = (String)headers.get(Constants.BUNDLE_SYMBOLICNAME);
        List<PathElement> paths = HeaderParser.parseHeader(symbolicName);

        String timeoutDirective = paths.get(0).getDirective(BlueprintConstants.TIMEOUT_DIRECTIVE);
        if (timeoutDirective != null) {
            LOGGER.debug("Timeout directive: " + timeoutDirective);
            timeout = Integer.parseInt(timeoutDirective);
        }

        String waitForDependenciesDirective = paths.get(0).getDirective(BlueprintConstants.WAIT_FOR_DEPENDENCIES_DIRECTIVE);
        if (waitForDependenciesDirective != null) {
            LOGGER.debug("Wait-for-dependencies directive: " + waitForDependenciesDirective);
            waitForDependencies = Boolean.parseBoolean(waitForDependenciesDirective);
        }

        String xmlValidationDirective = paths.get(0).getDirective(BlueprintConstants.XML_VALIDATION);
        if (xmlValidationDirective != null) {
            LOGGER.debug("Xml-validation directive: " + xmlValidationDirective);
            xmlValidation = Boolean.parseBoolean(xmlValidationDirective);
        }
    }
    
    public void schedule() {
        if (scheduled.compareAndSet(false, true)) {
            executors.submit(this);
        }
    }
    
    public void run() {
        scheduled.set(false);
        synchronized (scheduled) {
            doRun();
        }
    }

    /**
     * This method must be called inside a synchronized block to ensure this method is not run concurrently
     */
    private void doRun() {
        try {
            for (;;) {
                if (destroyed) {
                    return;
                }
                LOGGER.debug("Running blueprint container for bundle {} in state {}", bundleContext.getBundle().getSymbolicName(), state);
                switch (state) {
                    case Unknown:
                        checkDirectives();
                        eventDispatcher.blueprintEvent(new BlueprintEvent(BlueprintEvent.CREATING, getBundleContext().getBundle(), getExtenderBundle()));
                        parser = new Parser();
                        parser.setValidation(xmlValidation);
                        parser.parse(urls);
                        namespaces = parser.getNamespaces();
                        if (namespaces.size() > 0) {
                            handlers.addListener(this);
                        }
                        state = State.WaitForNamespaceHandlers;
                        break;
                    case WaitForNamespaceHandlers:
                    {
                        List<String> missing = new ArrayList<String>();
                        for (URI ns : namespaces) {
                            if (handlers.getNamespaceHandler(ns) == null) {
                                missing.add("(&(" + Constants.OBJECTCLASS + "=" + NamespaceHandler.class.getName() + ")(" + NamespaceHandlerRegistryImpl.NAMESPACE + "=" + ns + "))");
                            }
                        }
                        if (missing.size() > 0) {
                            eventDispatcher.blueprintEvent(new BlueprintEvent(BlueprintEvent.GRACE_PERIOD, getBundleContext().getBundle(), getExtenderBundle(), missing.toArray(new String[missing.size()])));
                            return;
                        }
                        parser.populate(handlers, componentDefinitionRegistry);
                        state = State.Populated;
                        break;
                    }
                    case Populated:
                        instantiator = new BlueprintObjectInstantiator(this, new RecipeBuilder(this).createRepository());
                        checkReferences();
                        trackServiceReferences();
                        Runnable r = new Runnable() {
                            public void run() {
                                synchronized (scheduled) {
                                    Throwable t = new TimeoutException();
                                    state = State.Failed;
                                    unregisterServices();
                                    untrackServiceReferences();
                                    destroyComponents();
                                    LOGGER.error("Unable to start blueprint container for bundle " + bundleContext.getBundle().getSymbolicName(), t);
                                    eventDispatcher.blueprintEvent(new BlueprintEvent(BlueprintEvent.FAILURE, getBundleContext().getBundle(), getExtenderBundle(), getMissingDependencies(), t));
                                }
                            }
                        };
                        timeoutFuture = executors.schedule(r, timeout, TimeUnit.MILLISECONDS);
                        if (checkAllSatisfiables() || !waitForDependencies) {
                            state = State.InitialReferencesSatisfied;
                        } else {
                            eventDispatcher.blueprintEvent(new BlueprintEvent(BlueprintEvent.GRACE_PERIOD, getBundleContext().getBundle(), getExtenderBundle(), getMissingDependencies()));
                            state = State.WaitForInitialReferences;
                        }
                        break;
                    case WaitForInitialReferences:
                        if (checkAllSatisfiables()) {
                            state = State.InitialReferencesSatisfied;
                            break;
                        } else {
                            return;
                        }
                    case InitialReferencesSatisfied:
                        processTypeConverters();
                        processProcessors();
                        // Update repository wrt bean processing
                        untrackServiceReferences();
                        DefaultRepository repository = (DefaultRepository) instantiator.getRepository();
                        DefaultRepository tmpRepo = new RecipeBuilder(this).createRepository();
                        for (String name : repository.getNames()) {
                            Recipe recipe = repository.getRecipe(name);
                            Object instance = repository.getInstance(name);
                            if (instance != null) {
                                tmpRepo.putRecipe(name, recipe);
                                tmpRepo.putInstance(name, instance);
                            }
                        }
                        satisfiables = null;
                        instantiator = new BlueprintObjectInstantiator(this, tmpRepo);
                        trackServiceReferences();
                        // Check references
                        if (checkAllSatisfiables() || !waitForDependencies) {
                            state = State.Create;
                        } else {
                            eventDispatcher.blueprintEvent(new BlueprintEvent(BlueprintEvent.GRACE_PERIOD, getBundleContext().getBundle(), getExtenderBundle(), getMissingDependencies()));
                            state = State.WaitForInitialReferences2;
                        }
                        break;
                    case WaitForInitialReferences2:
                        if (checkAllSatisfiables()) {
                            state = State.Create;
                            break;
                        } else {
                            return;
                        }
                    case Create:
                        timeoutFuture.cancel(false);
                        instantiateEagerSingletonBeans();
                        registerServices();

                        // Register the BlueprintContainer in the OSGi registry
                        if (registration == null) {
                            Properties props = new Properties();
                            props.put(BlueprintConstants.CONTAINER_SYMBOLIC_NAME_PROPERTY,
                                      bundleContext.getBundle().getSymbolicName());
                            props.put(BlueprintConstants.CONTAINER_VERSION_PROPERTY,
                                      bundleContext.getBundle().getHeaders().get(Constants.BUNDLE_VERSION));
                            registration = bundleContext.registerService(BlueprintContainer.class.getName(), this, props);
                            eventDispatcher.blueprintEvent(new BlueprintEvent(BlueprintEvent.CREATED, getBundleContext().getBundle(), getExtenderBundle()));
                            state = State.Created;
                        }
                        break;
                    case Created:
                    case Failed:
                        return;
                }
            }
        } catch (Throwable t) {
            state = State.Failed;
            if (timeoutFuture != null) {
                timeoutFuture.cancel(false);
            }
            unregisterServices();
            untrackServiceReferences();
            destroyComponents();
            LOGGER.error("Unable to start blueprint container for bundle " + bundleContext.getBundle().getSymbolicName(), t);
            eventDispatcher.blueprintEvent(new BlueprintEvent(BlueprintEvent.FAILURE, getBundleContext().getBundle(), getExtenderBundle(), t));
        }
    }

    private void checkReferences() throws Exception {
        DefaultRepository repository = (DefaultRepository) instantiator.getRepository();
        for (Recipe recipe : getAllRecipes()) {
            String ref = null;
            if (recipe instanceof RefRecipe) {
                ref = ((RefRecipe) recipe).getReferenceName();
            } else if (recipe instanceof IdRefRecipe) {
                ref = ((IdRefRecipe) recipe).getReferenceName();
            }
            if (ref != null && repository.get(ref) == null) {
                throw new ComponentDefinitionException("Unresolved ref/idref to component: " + ref);
            }
        }
    }

    private void processTypeConverters() throws Exception {
        List<String> typeConverters = new ArrayList<String>();
        for (Target target : componentDefinitionRegistry.getTypeConverters()) {
            if (target instanceof ComponentMetadata) {
                typeConverters.add(((ComponentMetadata) target).getId());
            } else if (target instanceof RefMetadata) {
                typeConverters.add(((RefMetadata) target).getComponentId());
            } else {
                throw new ComponentDefinitionException("Unexpected metadata for type converter: " + target);
            }
        }

        Map<String, Object> objects = instantiator.createAll(typeConverters.toArray(new String[typeConverters.size()]));
        for (Object obj : objects.values()) {
            if (obj instanceof Converter) {
                converter.registerConverter((Converter) obj);
            } else {
                throw new ComponentDefinitionException("Type converter " + obj + " does not implement the " + Converter.class.getName() + " interface");
            }
        }
    }

    private void processProcessors() throws Exception {
        // Instanciate ComponentDefinitionRegistryProcessor and BeanProcessor
        for (BeanMetadata bean : getMetadata(BeanMetadata.class)) {
            if (bean instanceof ExtendedBeanMetadata && !((ExtendedBeanMetadata) bean).isProcessor()) {
                continue;
            }
            Class clazz = bean.getRuntimeClass();
            if (clazz == null && bean.getClassName() != null) {
                clazz = loadClass(bean.getClassName());
            }
            if (clazz == null) {
                continue;
            }
            if (ComponentDefinitionRegistryProcessor.class.isAssignableFrom(clazz)) {
                Object obj = instantiator.create(bean.getId());
                ((ComponentDefinitionRegistryProcessor) obj).process(componentDefinitionRegistry);
            } else if (BeanProcessor.class.isAssignableFrom(clazz)) {
                Object obj = instantiator.create(bean.getId());
                this.beanProcessors.add((BeanProcessor) obj);
            }
        }
    }

    private Map<String, List<SatisfiableRecipe>> getSatisfiableDependenciesMap() {
        if (satisfiables == null && instantiator != null) {
            boolean createNewContext = !ExecutionContext.isContextSet();
            if (createNewContext) {
                ExecutionContext.setContext(new DefaultExecutionContext(this, instantiator.getRepository()));
            }
            try {
                satisfiables = new HashMap<String, List<SatisfiableRecipe>>();
                for (Recipe r : getAllRecipes()) {
                    List<SatisfiableRecipe> recipes = getAllRecipes(SatisfiableRecipe.class, r.getName());
                    if (!recipes.isEmpty()) {
                        satisfiables.put(r.getName(), recipes);
                    }
                }
                return satisfiables;
            } finally {
                if (createNewContext) {
                    ExecutionContext.setContext(null);
                }
            }
        }
        return satisfiables;
    }

    private void trackServiceReferences() {
        Map<String, List<SatisfiableRecipe>> dependencies = getSatisfiableDependenciesMap();
        List<String> satisfiables = new ArrayList<String>();
        for (String name : dependencies.keySet()) {
            for (SatisfiableRecipe satisfiable : dependencies.get(name)) {
                satisfiable.registerListener(this);
                satisfiable.start();
                satisfiables.add(satisfiable.getName());
            }
        }
        LOGGER.debug("Tracking service references: {}", satisfiables);
    }
    
    private void untrackServiceReferences() {
        Map<String, List<SatisfiableRecipe>> dependencies = getSatisfiableDependenciesMap();
        if (dependencies != null) {
            for (String name : dependencies.keySet()) {
                for (SatisfiableRecipe satisfiable : dependencies.get(name)) {
                    satisfiable.unregisterListener(this);
                    satisfiable.stop();
                }
            }
        }
    }

    private boolean checkAllSatisfiables() {
        Map<String, List<SatisfiableRecipe>> dependencies = getSatisfiableDependenciesMap();
        for (String name : dependencies.keySet()) {
            for (SatisfiableRecipe recipe : dependencies.get(name)) {
                if (!recipe.isSatisfied()) {
                    return false;
                }
            }
        }
        return true;
    }

    public void notifySatisfaction(SatisfiableRecipe satisfiable) {
        LOGGER.debug("Notified satisfaction {} in bundle {}: {}",
                new Object[] { satisfiable.getName(), bundleContext.getBundle().getSymbolicName(), satisfiable.isSatisfied() });
        if (state == State.WaitForInitialReferences) {
            schedule();
        } else if (state == State.Created) {
            Map<String, List<SatisfiableRecipe>> dependencies = getSatisfiableDependenciesMap();
            for (String name : dependencies.keySet()) {
                ComponentMetadata metadata = componentDefinitionRegistry.getComponentDefinition(name);
                if (metadata instanceof ServiceMetadata) {
                    boolean satisfied = true;
                    for (SatisfiableRecipe recipe : dependencies.get(name)) {
                        if (!recipe.isSatisfied()) {
                            satisfied = false;
                            break;
                        }
                    }
                    ServiceRecipe reg = (ServiceRecipe) getComponentInstance(name);
                    if (satisfied && !reg.isRegistered()) {
                        LOGGER.debug("Registering service {} due to satisfied references", name);
                        reg.register();
                    } else if (!satisfied && reg.isRegistered()) {
                        LOGGER.debug("Unregistering service {} due to unsatisfied references", name);
                        reg.unregister();
                    }
                }
            }
        }
    }

    private void instantiateEagerSingletonBeans() {
        List<String> components = new ArrayList<String>();
        for (String name : componentDefinitionRegistry.getComponentDefinitionNames()) {
            ComponentMetadata component = componentDefinitionRegistry.getComponentDefinition(name);
            if (component instanceof BeanMetadata) {
                BeanMetadata local = (BeanMetadata) component;
                String scope = local.getScope();
                if (!local.isLazyInit() && BeanMetadata.SCOPE_SINGLETON.equals(scope)) {
                    components.add(name);
                }
            } else {
                if (component instanceof ServiceMetadata) {
                    components.add(name);
                }
            }
        }
        LOGGER.debug("Instantiating components: {}", components);
        try {
            instantiator.createAll(components);
        } catch (ComponentDefinitionException e) {
            throw e;
        } catch (Throwable t) {
            throw new ComponentDefinitionException("Unable to instantiate components", t);
        }
    }

    private void registerServices() {
        List<ServiceRecipe> recipes = getAllRecipes(ServiceRecipe.class);
        for (ServiceRecipe r : recipes) {
            List<SatisfiableRecipe> dependencies = getSatisfiableDependenciesMap().get(r.getName());
            boolean satisfied = true;
            if (dependencies != null) {
                for (SatisfiableRecipe recipe : dependencies) {
                    if (!recipe.isSatisfied()) {
                        satisfied = false;
                        break;
                    }
                }
            }
            if (satisfied) {
                r.register();
            }
        }
    }

    private void unregisterServices() {
        if (instantiator != null) {
            List<ServiceRecipe> recipes = getAllRecipes(ServiceRecipe.class);
            for (ServiceRecipe r : recipes) {
                r.unregister();
            }
        }
    }

    private <T> List<T> getAllRecipes(Class<T> clazz, String... names) {
        List<T> recipes = new ArrayList<T>();
        for (Recipe r : getAllRecipes(names)) {
            if (clazz.isInstance(r)) {
                recipes.add(clazz.cast(r));
            }
        }
        return recipes;
    }

    private Set<Recipe> getAllRecipes(String... names) {
        boolean createNewContext = !ExecutionContext.isContextSet();
        if (createNewContext) {
            ExecutionContext.setContext(new DefaultExecutionContext(this, instantiator.getRepository()));
        }
        try {
            Set<Recipe> recipes = new HashSet<Recipe>();
            DefaultRepository repo = (DefaultRepository) instantiator.getRepository();
            Set<String> topLevel = names != null && names.length > 0 ? new HashSet<String>(Arrays.asList(names)) : repo.getNames();
            for (String name : topLevel) {
                internalGetAllRecipes(recipes, repo.getRecipe(name));
            }
            return recipes;
        } finally {
            if (createNewContext) {
                ExecutionContext.setContext(null);
            }
        }
    }

    /*
     * This method should not be called directly, only from one of the getAllRecipes() methods.
     */
    private void internalGetAllRecipes(Set<Recipe> recipes, Recipe r) {
        if (r != null) {
            if (recipes.add(r)) {
                for (Recipe c : r.getNestedRecipes()) {
                    internalGetAllRecipes(recipes, c);
                }
            }
        }
    }

    private void destroyComponents() {
        if (instantiator != null) {
            ((DefaultRepository)instantiator.getRepository()).destroy();
        }
        
        Map<String, Destroyable> destroyables = new HashMap<String, Destroyable>(this.destroyables);
        this.destroyables.clear();
        for (Map.Entry<String, Destroyable> entry : destroyables.entrySet()) {
            try {
                entry.getValue().destroy();
            } catch (Exception e) {
                LOGGER.info("Error destroying bean " + entry.getKey(), e);
            }
        }
    }

    private String[] getMissingDependencies() {
        List<String> missing = new ArrayList<String>();
        Map<String, List<SatisfiableRecipe>> dependencies = getSatisfiableDependenciesMap();
        for (String name : dependencies.keySet()) {
            for (SatisfiableRecipe recipe : dependencies.get(name)) {
                if (!recipe.isSatisfied()) {
                    missing.add(recipe.getOsgiFilter());
                }
            }
        }
        return missing.toArray(new String[missing.size()]);
    }
    
    public Set<String> getComponentIds() {
        return componentDefinitionRegistry.getComponentDefinitionNames();
    }
    
    public Object getComponentInstance(String id) throws NoSuchComponentException {
        if (instantiator == null) {
            throw new NoSuchComponentException(id);
        }
        try {
            LOGGER.debug("Instantiating component {}", id);
            return instantiator.create(id);
        } catch (ComponentDefinitionException e) {
            throw e;
        } catch (Throwable t) {
            throw new ComponentDefinitionException("Cound not create component instance for " + id, t);
        }
    }

    public ComponentMetadata getComponentMetadata(String id) {
        ComponentMetadata metadata = componentDefinitionRegistry.getComponentDefinition(id);
        if (metadata == null) {
            throw new NoSuchComponentException(id);
        }
        return metadata;
    }

    public <T extends ComponentMetadata> Collection<T> getMetadata(Class<T> clazz) {
        Collection<T> metadatas = new ArrayList<T>();
        for (String name : componentDefinitionRegistry.getComponentDefinitionNames()) {
            ComponentMetadata component = componentDefinitionRegistry.getComponentDefinition(name);
            getMetadata(clazz, component, metadatas);
        }
        metadatas = Collections.unmodifiableCollection(metadatas);
        return metadatas;
    }

    private <T extends ComponentMetadata> void getMetadata(Class<T> clazz, Metadata component, Collection<T> metadatas) {
        if (component == null) {
            return;
        }
        if (clazz.isInstance(component)) {
            metadatas.add(clazz.cast(component));
        }
        if (component instanceof BeanMetadata) {
            getMetadata(clazz, ((BeanMetadata) component).getFactoryComponent(), metadatas);
            for (BeanArgument arg : ((BeanMetadata) component).getArguments()) {
                getMetadata(clazz, arg.getValue(), metadatas);
            }
            for (BeanProperty prop : ((BeanMetadata) component).getProperties()) {
                getMetadata(clazz, prop.getValue(), metadatas);
            }
        }
        if (component instanceof CollectionMetadata) {
            for (Metadata m : ((CollectionMetadata) component).getValues()) {
                getMetadata(clazz, m, metadatas);
            }
        }
        if (component instanceof MapMetadata) {
            for (MapEntry m : ((MapMetadata) component).getEntries()) {
                getMetadata(clazz, m.getKey(), metadatas);
                getMetadata(clazz, m.getValue(), metadatas);
            }
        }
        if (component instanceof PropsMetadata) {
            for (MapEntry m : ((PropsMetadata) component).getEntries()) {
                getMetadata(clazz, m.getKey(), metadatas);
                getMetadata(clazz, m.getValue(), metadatas);
            }
        }
        if (component instanceof ServiceReferenceMetadata) {
            for (Listener l : ((ServiceReferenceMetadata) component).getServiceListeners()) {
                getMetadata(clazz, l.getListenerComponent(), metadatas);
            }
        }
        if (component instanceof RefCollectionMetadata) {
            getMetadata(clazz, ((RefCollectionMetadata) component).getComparator(), metadatas);
        }
        if (component instanceof ServiceMetadata) {
            getMetadata(clazz, ((ServiceMetadata) component).getServiceComponent(), metadatas);
            for (MapEntry m : ((ServiceMetadata) component).getServiceProperties()) {
                getMetadata(clazz, m.getKey(), metadatas);
                getMetadata(clazz, m.getValue(), metadatas);
            }
            for (RegistrationListener l : ((ServiceMetadata) component).getRegistrationListeners()) {
                getMetadata(clazz, l.getListenerComponent(), metadatas);
            }
        }
    }

    public Repository getRepository() {
        return instantiator.getRepository();
    }
    
    public Converter getConverter() {
        return converter;
    }
    
    public ComponentDefinitionRegistryImpl getComponentDefinitionRegistry() {
        return componentDefinitionRegistry;
    }
        
    public BundleContext getBundleContext() {
        return bundleContext;
    }
    
    public void destroy() {
        destroyed = true;
        eventDispatcher.blueprintEvent(new BlueprintEvent(BlueprintEvent.DESTROYING, getBundleContext().getBundle(), getExtenderBundle()));

        if (timeoutFuture != null) {
            timeoutFuture.cancel(false);
        }
        if (registration != null) {
            registration.unregister();
        }
        handlers.removeListener(this);
        unregisterServices();
        untrackServiceReferences();
        destroyComponents();
        
        eventDispatcher.blueprintEvent(new BlueprintEvent(BlueprintEvent.DESTROYED, getBundleContext().getBundle(), getExtenderBundle()));
        LOGGER.debug("Module container destroyed: " + this.bundleContext);
    }

    public void namespaceHandlerRegistered(URI uri) {
        if (namespaces != null && namespaces.contains(uri)) {
            schedule();
        }
    }

    public void namespaceHandlerUnregistered(URI uri) {
        if (namespaces != null && namespaces.contains(uri)) {
            unregisterServices();
            untrackServiceReferences();
            destroyComponents();
            state = State.WaitForNamespaceHandlers;
            schedule();
        }
    }

}


/* TODO: fix the following deadlock

      [bnd] "pool-3-thread-1" prio=5 tid=0x01018790 nid=0x8a2c00 in Object.wait() [0xb0f90000..0xb0f90d90]
      [bnd] 	at java.lang.Object.wait(Native Method)
      [bnd] 	- waiting on <0x25671928> (a java.lang.Object)
      [bnd] 	at org.apache.geronimo.blueprint.container.UnaryServiceReferenceRecipe.getService(UnaryServiceReferenceRecipe.java:197)
      [bnd] 	- locked <0x25671928> (a java.lang.Object)
      [bnd] 	at org.apache.geronimo.blueprint.container.UnaryServiceReferenceRecipe.access$000(UnaryServiceReferenceRecipe.java:55)
      [bnd] 	at org.apache.geronimo.blueprint.container.UnaryServiceReferenceRecipe$ServiceDispatcher.loadObject(UnaryServiceReferenceRecipe.java:225)
      [bnd] 	at org.osgi.test.cases.blueprint.services.ServiceManager$$EnhancerByCGLIB$$f740783d.getActiveServices(<generated>)
      [bnd] 	at org.osgi.test.cases.blueprint.components.serviceimport.NullReferenceList.init(NullReferenceList.java:43)
      [bnd] 	at sun.reflect.NativeMethodAccessorImpl.invoke0(Native Method)
      [bnd] 	at sun.reflect.NativeMethodAccessorImpl.invoke(NativeMethodAccessorImpl.java:39)
      [bnd] 	at sun.reflect.DelegatingMethodAccessorImpl.invoke(DelegatingMethodAccessorImpl.java:25)
      [bnd] 	at java.lang.reflect.Method.invoke(Method.java:585)
      [bnd] 	at org.apache.geronimo.blueprint.container.BlueprintObjectRecipe.internalCreate(BlueprintObjectRecipe.java:586)
      [bnd] 	at org.apache.geronimo.blueprint.di.AbstractRecipe.create(AbstractRecipe.java:95)
      [bnd] 	at org.apache.geronimo.blueprint.container.BlueprintObjectInstantiator.createInstance(BlueprintObjectInstantiator.java:83)
      [bnd] 	at org.apache.geronimo.blueprint.container.BlueprintObjectInstantiator.createAll(BlueprintObjectInstantiator.java:65)
      [bnd] 	at org.apache.geronimo.blueprint.container.BlueprintContextImpl.instantiateComponents(BlueprintContextImpl.java:541)
      [bnd] 	at org.apache.geronimo.blueprint.container.BlueprintContextImpl.run(BlueprintContextImpl.java:303)
      [bnd] 	- locked <0x25730658> (a org.apache.geronimo.blueprint.container.BlueprintContextImpl)
      [bnd] 	at java.util.concurrent.Executors$RunnableAdapter.call(Executors.java:417)
      [bnd] 	at java.util.concurrent.FutureTask$Sync.innerRun(FutureTask.java:269)
      [bnd] 	at java.util.concurrent.FutureTask.run(FutureTask.java:123)
      [bnd] 	at java.util.concurrent.ThreadPoolExecutor$Worker.runTask(ThreadPoolExecutor.java:650)
      [bnd] 	at java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:675)
      [bnd] 	at java.lang.Thread.run(Thread.java:613)
      [bnd]
      [bnd] "main" prio=5 tid=0x01001460 nid=0xb0801000 waiting for monitor entry [0xb07ff000..0xb0800148]
      [bnd] 	at org.apache.geronimo.blueprint.container.BlueprintContextImpl.destroy(BlueprintContextImpl.java:687)
      [bnd] 	- waiting to lock <0x25730658> (a org.apache.geronimo.blueprint.container.BlueprintContextImpl)
      [bnd] 	at org.apache.geronimo.blueprint.BlueprintExtender.destroyContext(BlueprintExtender.java:121)
      [bnd] 	at org.apache.geronimo.blueprint.BlueprintExtender.bundleChanged(BlueprintExtender.java:113)
      [bnd] 	at org.eclipse.osgi.framework.internal.core.BundleContextImpl.dispatchEvent(BundleContextImpl.java:916)
      [bnd] 	at org.eclipse.osgi.framework.eventmgr.EventManager.dispatchEvent(EventManager.java:220)
      [bnd] 	at org.eclipse.osgi.framework.eventmgr.ListenerQueue.dispatchEventSynchronous(ListenerQueue.java:149)
      [bnd] 	at org.eclipse.osgi.framework.internal.core.Framework.publishBundleEventPrivileged(Framework.java:1350)
      [bnd] 	at org.eclipse.osgi.framework.internal.core.Framework.publishBundleEvent(Framework.java:1301)
      [bnd] 	at org.eclipse.osgi.framework.internal.core.BundleHost.stopWorker(BundleHost.java:470)
      [bnd] 	at org.eclipse.osgi.framework.internal.core.AbstractBundle.uninstallWorker(AbstractBundle.java:784)
      [bnd] 	at org.eclipse.osgi.framework.internal.core.AbstractBundle.uninstall(AbstractBundle.java:764)
      [bnd] 	at org.osgi.test.cases.blueprint.framework.BlueprintMetadata.cleanup(BlueprintMetadata.java:670)
      [bnd] 	at org.osgi.test.cases.blueprint.framework.EventSet.stop(EventSet.java:97)
      [bnd] 	at org.osgi.test.cases.blueprint.framework.TestPhase.stopEventSets(TestPhase.java:119)
      [bnd] 	at org.osgi.test.cases.blueprint.framework.TestPhase.cleanup(TestPhase.java:98)
      [bnd] 	at org.osgi.test.cases.blueprint.framework.BaseTestController.cleanup(BaseTestController.java:219)
      [bnd] 	at org.osgi.test.cases.blueprint.framework.StandardTestController.cleanup(StandardTestController.java:177)
      [bnd] 	at org.osgi.test.cases.blueprint.framework.BaseTestController.terminate(BaseTestController.java:340)
      [bnd] 	at org.osgi.test.cases.blueprint.framework.BaseTestController.run(BaseTestController.java:363)
      [bnd] 	at org.osgi.test.cases.blueprint.tests.TestReferenceCollection.testEmptyListCollectionServiceListener(TestReferenceCollection.java:527)
      [bnd] 	at sun.reflect.NativeMethodAccessorImpl.invoke0(Native Method)
      [bnd] 	at sun.reflect.NativeMethodAccessorImpl.invoke(NativeMethodAccessorImpl.java:39)
      [bnd] 	at sun.reflect.DelegatingMethodAccessorImpl.invoke(DelegatingMethodAccessorImpl.java:25)
      [bnd] 	at java.lang.reflect.Method.invoke(Method.java:585)
      [bnd] 	at junit.framework.TestCase.runTest(TestCase.java:164)
      [bnd] 	at junit.framework.TestCase.runBare(TestCase.java:130)
      [bnd] 	at junit.framework.TestResult$1.protect(TestResult.java:106)
      [bnd] 	at junit.framework.TestResult.runProtected(TestResult.java:124)
      [bnd] 	at junit.framework.TestResult.run(TestResult.java:109)
      [bnd] 	at junit.framework.TestCase.run(TestCase.java:120)
      [bnd] 	at junit.framework.TestSuite.runTest(TestSuite.java:230)
      [bnd] 	at junit.framework.TestSuite.run(TestSuite.java:225)
      [bnd] 	at junit.framework.TestSuite.runTest(TestSuite.java:230)
      [bnd] 	at junit.framework.TestSuite.run(TestSuite.java:225)
      [bnd] 	at aQute.junit.runtime.Target.doTesting(Target.java:157)
      [bnd] 	at aQute.junit.runtime.Target.run(Target.java:40)
      [bnd] 	at aQute.junit.runtime.Target.main(Target.java:33)

*/