/**
 * Copyright (c) 2000-present Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.portal.wab.extender.internal;

import com.liferay.portal.wab.extender.internal.artifact.WarArtifactTransformer;
import com.liferay.portal.wab.extender.internal.handler.WabURLStreamHandlerService;

import java.util.Hashtable;

import org.apache.felix.fileinstall.ArtifactUrlTransformer;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.url.URLConstants;
import org.osgi.service.url.URLStreamHandlerService;

/**
 * @author Miguel Pastor
 * @author Raymond Augé
 */
public class WabExtenderActivator implements BundleActivator {

	public void start(BundleContext bundleContext) throws Exception {
		_registerStreamHandler(bundleContext);

		_registerArtifactTransformer(bundleContext);
	}

	public void stop(BundleContext bundleContext) throws Exception {
		_serviceRegistration.unregister();

		_serviceRegistration = null;
	}

	private void _registerArtifactTransformer(BundleContext bundleContext) {
		_serviceRegistration = bundleContext.registerService(
			ArtifactUrlTransformer.class, new WarArtifactTransformer(), null);
	}

	private void _registerStreamHandler(BundleContext bundleContext) {
		Bundle bundle = bundleContext.getBundle(0);

		Class clazz = bundle.getClass();

		ClassLoader classLoader = clazz.getClassLoader();

		Hashtable<String, Object> properties = new Hashtable<String, Object>();

		properties.put(
			URLConstants.URL_HANDLER_PROTOCOL, new String[] {"webbundle"});

		bundleContext.registerService(
			URLStreamHandlerService.class.getName(),
			new WabURLStreamHandlerService(bundleContext, classLoader),
			properties
		);
	}

	private ServiceRegistration<ArtifactUrlTransformer> _serviceRegistration;

}