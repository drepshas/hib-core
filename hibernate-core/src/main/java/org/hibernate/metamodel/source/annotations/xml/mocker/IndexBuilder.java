/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * Copyright (c) 2011, Red Hat Inc. or third-party contributors as
 * indicated by the @author tags or express copyright attribution
 * statements applied by the authors.  All third-party contributions are
 * distributed under license by Red Hat Inc..
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, write to:
 * Free Software Foundation, Inc.
 * 51 Franklin Street, Fifth Floor
 * Boston, MA  02110-1301  USA
 */
package org.hibernate.metamodel.source.annotations.xml.mocker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.Index;
import org.jboss.logging.Logger;

import org.hibernate.AssertionFailure;
import org.hibernate.internal.CoreMessageLogger;
import org.hibernate.internal.util.StringHelper;
import org.hibernate.metamodel.source.annotations.xml.filter.IndexedAnnotationFilter;
import org.hibernate.service.ServiceRegistry;
import org.hibernate.service.classloading.spi.ClassLoaderService;

/**
 * @author Strong Liu
 */
public class IndexBuilder {
	private static final CoreMessageLogger LOG = Logger.getMessageLogger(
			CoreMessageLogger.class,
			IndexBuilder.class.getName()
	);
	private Map<DotName, List<AnnotationInstance>> annotations;
	private Map<DotName, List<ClassInfo>> subclasses;
	private Map<DotName, List<ClassInfo>> implementors;
	private Map<DotName, ClassInfo> classes;
	private Index index;
	private Map<DotName, Map<DotName, List<AnnotationInstance>>> classInfoAnnotationsMap;
	private Map<DotName, Map<DotName, List<AnnotationInstance>>> indexedClassInfoAnnotationsMap;
	private ServiceRegistry serviceRegistry;

	IndexBuilder(Index index, ServiceRegistry serviceRegistry) {
		this.index = index;
		this.serviceRegistry = serviceRegistry;
		this.annotations = new HashMap<DotName, List<AnnotationInstance>>();
		this.subclasses = new HashMap<DotName, List<ClassInfo>>();
		this.implementors = new HashMap<DotName, List<ClassInfo>>();
		this.classes = new HashMap<DotName, ClassInfo>();
		this.classInfoAnnotationsMap = new HashMap<DotName, Map<DotName, List<AnnotationInstance>>>();
		this.indexedClassInfoAnnotationsMap = new HashMap<DotName, Map<DotName, List<AnnotationInstance>>>();
	}

	Index build(EntityMappingsMocker.Default globalDefaults) {
		if ( !classInfoAnnotationsMap.isEmpty() ) {
			throw new AssertionFailure( "IndexBuilder.finishEntityObject must be called after processing each entity." );
		}
		//merge annotations that not overrided by xml into the new Index
		for ( ClassInfo ci : index.getKnownClasses() ) {
			DotName name = ci.name();
			// annotations classes NOT overrided by xml
			if ( !indexedClassInfoAnnotationsMap.containsKey( name ) ) {
				if ( ci.annotations() != null && !ci.annotations().isEmpty() ) {
					Map<DotName, List<AnnotationInstance>> tmp = new HashMap<DotName, List<AnnotationInstance>>( ci.annotations() );
					DefaultConfigurationHelper.INSTANCE.apply( tmp, globalDefaults );
					mergeAnnotationMap( tmp, annotations );
					tmp.clear();
					classes.put( name, ci );
					if ( ci.superName() != null ) {
						addSubClasses( ci.superName(), ci );
					}
					if ( ci.interfaces() != null && ci.interfaces().length > 0 ) {
						addImplementors( ci.interfaces(), ci );
					}
				}
			}
		}
		Index newIndex = Index.create(
				annotations, subclasses, implementors, classes
		);
		if ( LOG.isTraceEnabled() ) {
			LOG.trace( "Annotations from new build Index:" );
			newIndex.printAnnotations();
		}
		return newIndex;
	}

	void mappingMetadataComplete(EntityMappingsMocker.Default globalDefaults) {
		if ( globalDefaults != null && globalDefaults.getMetadataComplete() != null && globalDefaults.getMetadataComplete() ) {
			LOG.debug(
					"xml-mapping-metadata-complete is specified in persistence-unit-metadata, ignore JPA annotations."
			);
			index = Index.create(
					new HashMap<DotName, List<AnnotationInstance>>(),
					new HashMap<DotName, List<ClassInfo>>(),
					new HashMap<DotName, List<ClassInfo>>(),
					new HashMap<DotName, ClassInfo>()
			);
		}

	}

	/**
	 * @param name Entity Object dot name which is being process.
	 * @param metadataComplete True Entity Object defined in orm.xml is supposed to override annotations.
	 */
	void metadataComplete(DotName name, boolean metadataComplete) {
		if ( metadataComplete ) {
			getIndexedAnnotations( name ).clear();
		}
	}

	public Map<DotName, List<AnnotationInstance>> getIndexedAnnotations(DotName name) {
		Map<DotName, List<AnnotationInstance>> map = indexedClassInfoAnnotationsMap.get( name );
		if ( map == null ) {
			ClassInfo ci = index.getClassByName( name );
			if ( ci == null || ci.annotations() == null ) {
				map = Collections.emptyMap();
			}
			else {
				map = new HashMap<DotName, List<AnnotationInstance>>( ci.annotations() );
				//here we ignore global annotations
				for ( DotName globalAnnotationName : IndexedAnnotationFilter.GLOBAL_ANNOTATIONS ) {
					if ( map.containsKey( globalAnnotationName ) ) {
						map.put( globalAnnotationName, Collections.<AnnotationInstance>emptyList() );
					}
				}
			}
			indexedClassInfoAnnotationsMap.put( name, map );
		}
		return map;
	}

	void collectGlobalConfigurationFromIndex(GlobalAnnotations globalAnnotations) {
		for ( DotName annName : IndexedAnnotationFilter.GLOBAL_ANNOTATIONS ) {
			List<AnnotationInstance> annotationInstanceList = index.getAnnotations( annName );
			if ( MockHelper.isNotEmpty( annotationInstanceList ) ) {
				globalAnnotations.addIndexedAnnotationInstance( annotationInstanceList );
			}
		}
		globalAnnotations.filterIndexedAnnotations();
	}

	void finishGlobalConfigurationMocking(GlobalAnnotations globalAnnotations) {
		annotations.putAll( globalAnnotations.getAnnotationInstanceMap() );

	}

	void finishEntityObject(final DotName name, final EntityMappingsMocker.Default defaults) {
		// annotations classes overrided by xml
		if ( indexedClassInfoAnnotationsMap.containsKey( name ) ) {
			Map<DotName, List<AnnotationInstance>> tmp = getIndexedAnnotations( name );
			DefaultConfigurationHelper.INSTANCE.apply( tmp, defaults );
			mergeAnnotationMap( tmp, classInfoAnnotationsMap.get( name ) );
			tmp.clear();
		}
		Map<DotName, List<AnnotationInstance>> map = classInfoAnnotationsMap.remove( name );
		if ( map == null ) {
			throw new AssertionFailure( "Calling finish entity object " + name.toString() + " before create it." );
		}

		mergeAnnotationMap( map, annotations );
	}


	void addAnnotationInstance(DotName targetClassName, AnnotationInstance annotationInstance) {
		if ( annotationInstance == null ) {
			return;
		}
		for ( IndexedAnnotationFilter indexedAnnotationFilter : IndexedAnnotationFilter.filters ) {
			indexedAnnotationFilter.beforePush( this, targetClassName, annotationInstance );
		}
		Map<DotName, List<AnnotationInstance>> map = classInfoAnnotationsMap.get( targetClassName );
		if ( map == null ) {
			throw new AssertionFailure( "Can't find " + targetClassName + " in internal cache, should call createClassInfo first" );
		}

		List<AnnotationInstance> annotationInstanceList = map.get( annotationInstance.name() );
		if ( annotationInstanceList == null ) {
			annotationInstanceList = new ArrayList<AnnotationInstance>();
			map.put( annotationInstance.name(), annotationInstanceList );
		}
		annotationInstanceList.add( annotationInstance );
	}

	ServiceRegistry getServiceRegistry() {
		return serviceRegistry;
	}

	ClassInfo createClassInfo(String className) {
		if ( StringHelper.isEmpty( className ) ) {
			throw new AssertionFailure( "Class Name used to create ClassInfo is empty." );
		}
		DotName classDotName = DotName.createSimple( className );
		if ( classes.containsKey( classDotName ) ) {
			LOG.warnf(
					"Class %s has already been processed by IndexBuilder, ignoring this call and return previous created ClassInfo object"
			);
			return classes.get( classDotName );
		}
		Class clazz = serviceRegistry.getService( ClassLoaderService.class ).classForName( className );
		DotName superName = null;
		DotName[] interfaces = null;
		short access_flag;
		ClassInfo annClassInfo = index.getClassByName( classDotName );
		if ( annClassInfo != null ) {
			superName = annClassInfo.superName();
			interfaces = annClassInfo.interfaces();
			access_flag = annClassInfo.flags();
		}
		else {
			Class superClass = clazz.getSuperclass();
			if ( superClass != null ) {
				superName = DotName.createSimple( superClass.getName() );
			}
			Class[] classInterfaces = clazz.getInterfaces();
			if ( classInterfaces != null && classInterfaces.length > 0 ) {
				interfaces = new DotName[classInterfaces.length];
				for ( int i = 0; i < classInterfaces.length; i++ ) {
					interfaces[i] = DotName.createSimple( classInterfaces[i].getName() );
				}
			}
			//todo how to get access_flag from a Class?
			access_flag = 0x0001;
		}
		Map<DotName, List<AnnotationInstance>> map = new HashMap<DotName, List<AnnotationInstance>>();
		classInfoAnnotationsMap.put( classDotName, map );
		ClassInfo classInfo = ClassInfo.create(
				classDotName, superName, access_flag, interfaces, map
		);
		classes.put( classDotName, classInfo );
		addSubClasses( superName, classInfo );
		addImplementors( interfaces, classInfo );
		return classInfo;
	}

	private void addSubClasses(DotName superClassDotName, ClassInfo classInfo) {
		if ( superClassDotName != null ) {
			List<ClassInfo> classInfoList = subclasses.get( superClassDotName );
			if ( classInfoList == null ) {
				classInfoList = new ArrayList<ClassInfo>();
				subclasses.put( superClassDotName, classInfoList );
			}
			classInfoList.add( classInfo );
		}
	}

	private void addImplementors(DotName[] dotNames, ClassInfo classInfo) {
		if ( dotNames != null && dotNames.length > 0 ) {
			for ( DotName dotName : dotNames ) {
				List<ClassInfo> classInfoList = implementors.get( dotName );
				if ( classInfoList == null ) {
					classInfoList = new ArrayList<ClassInfo>();
					implementors.put( dotName, classInfoList );
				}
				classInfoList.add( classInfo );
			}
		}
	}

	//merge source into target
	private void mergeAnnotationMap(Map<DotName, List<AnnotationInstance>> source, Map<DotName, List<AnnotationInstance>> target) {
		if ( source != null && !source.isEmpty() ) {
			for ( DotName annotationName : source.keySet() ) {
				if ( source.get( annotationName ).isEmpty() ) {
					continue;
				}
				List<AnnotationInstance> annotationInstanceList = target.get( annotationName );
				if ( annotationInstanceList == null ) {
					annotationInstanceList = new ArrayList<AnnotationInstance>();
					target.put( annotationName, annotationInstanceList );
				}
				annotationInstanceList.addAll( source.get( annotationName ) );
			}
		}
	}


}