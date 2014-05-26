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

package com.liferay.portal.portlet.tracker.internal;

import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.exception.SystemException;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.portlet.LiferayWindowState;
import com.liferay.portal.kernel.portlet.PortletBag;
import com.liferay.portal.kernel.portlet.PortletBagPool;
import com.liferay.portal.kernel.util.CharPool;
import com.liferay.portal.kernel.util.ContentTypes;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.SetUtil;
import com.liferay.portal.kernel.util.StringUtil;
import com.liferay.portal.kernel.util.Validator;
import com.liferay.portal.model.Company;
import com.liferay.portal.model.CompanyConstants;
import com.liferay.portal.model.PortletCategory;
import com.liferay.portal.model.PortletConstants;
import com.liferay.portal.model.PortletInfo;
import com.liferay.portal.model.impl.PortletImpl;
import com.liferay.portal.security.permission.ResourceActions;
import com.liferay.portal.service.CompanyLocalService;
import com.liferay.portal.service.PortletLocalService;
import com.liferay.portal.service.ResourceActionLocalService;
import com.liferay.portal.util.PortletKeys;
import com.liferay.portal.util.WebAppPool;
import com.liferay.portal.util.WebKeys;
import com.liferay.portlet.PortletBagFactory;
import com.liferay.portlet.PortletInstanceFactory;
import com.liferay.registry.util.StringPlus;
import com.liferay.util.JS;

import java.io.IOException;

import java.net.URL;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.portlet.Portlet;
import javax.portlet.PortletMode;
import javax.portlet.WindowState;

import javax.servlet.ServletContext;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Filter;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;

/**
 * @author Raymond Augé
 */
@Component(
	immediate = true, service = PortletTracker.class
)
public class PortletTracker
	implements ServiceTrackerCustomizer<Portlet, Portlet> {

	@Override
	public Portlet addingService(ServiceReference<Portlet> serviceReference) {
		String portletName = (String)serviceReference.getProperty(
			"javax.portlet.name");

		try {
			String portletId = JS.getSafeName(portletName);

			if (portletId.length() > _PORTLET_ID_MAX_LENGTH) {
				throw new IllegalArgumentException(
					"Portlet id " + portletId + " has more than " +
						_PORTLET_ID_MAX_LENGTH + " characters");
			}

			if (_log.isInfoEnabled()) {
				_log.info("Adding " + serviceReference);
			}

			BundleContext bundleContext = _componentContext.getBundleContext();

			Portlet portlet = bundleContext.getService(serviceReference);

			addingPortlet(serviceReference, portlet, portletName, portletId);

			return portlet;
		}
		catch (Throwable t) {
			_log.error(t, t);
		}

		return null;
	}

	@Override
	public void modifiedService(
		ServiceReference<Portlet> serviceReference, Portlet portlet) {

		removedService(serviceReference, portlet);

		addingService(serviceReference);
	}

	@Override
	public void removedService(
		ServiceReference<Portlet> serviceReference, Portlet portlet) {

		String portletName = (String)serviceReference.getProperty(
			"javax.portlet.name");

		if (Validator.isNull(portletName)) {
			return;
		}

		if (_log.isInfoEnabled()) {
			_log.info("Removing " + serviceReference);
		}

		String portletId = JS.getSafeName(portletName);

		com.liferay.portal.model.Portlet portletModel =
			_portletLocalService.getPortletById(portletId);

		_portletInstanceFactory.destroy(portletModel);

		List<Company> companies = null;

		try {
			companies = _companyLocalService.getCompanies();
		}
		catch (SystemException se) {
			throw new RuntimeException(se);
		}

		for (Company company : companies) {
			PortletCategory portletCategory = (PortletCategory)WebAppPool.get(
				company.getCompanyId(), WebKeys.PORTLET_CATEGORY);

			portletCategory.separate(portletId);
		}

		PortletBag portletBag = PortletBagPool.remove(portletId);

		if (portletBag != null) {
			portletBag.destroy();
		}
	}

	@Activate
	protected void activate(ComponentContext componentContext) {
		_componentContext = componentContext;

		BundleContext bundleContext = _componentContext.getBundleContext();

		Filter filter = null;

		try {
			filter = bundleContext.createFilter(
				"(&(javax.portlet.name=*)(objectClass=" +
					Portlet.class.getName() + "))");
		}
		catch (InvalidSyntaxException ise) {
			throw new RuntimeException(ise);
		}

		_serviceTracker = new ServiceTracker<Portlet, Portlet>(
			bundleContext, filter, this);

		_serviceTracker.open();

		if (_log.isInfoEnabled()) {
			_log.info("Activated");
		}
	}

	protected void addingPortlet(
			ServiceReference<Portlet> serviceReference, Portlet portlet,
			String portletName, String portletId)
		throws Exception {

		com.liferay.portal.model.Portlet portletModel = buildPortletModel(
			portletId);

		portletModel.setPortletName(portletName);

		String displayName = GetterUtil.getString(
			serviceReference.getProperty("javax.portlet.display-name"),
			portletName);

		portletModel.setDisplayName(displayName);

		Class<?> portletClazz = portlet.getClass();

		portletModel.setPortletClass(portletClazz.getName());

		collectCacheScope(serviceReference, portletModel);
		collectExpirationCache(serviceReference, portletModel);
		collectInitParams(serviceReference, portletModel);
		collectPortletInfo(serviceReference, portletModel, displayName);
		collectPortletModes(serviceReference, portletModel);
		collectPortletPreferences(serviceReference, portletModel);
		collectSecurityRoleRefs(serviceReference, portletModel);
		collectWindowStates(serviceReference, portletModel);

		Bundle bundle = serviceReference.getBundle();

		PortletBagFactory portletBagFactory = new BundlePortletBagFactory(
			bundle, portlet);

		BundleWiring bundleWiring = bundle.adapt(BundleWiring.class);

		portletBagFactory.setClassLoader(bundleWiring.getClassLoader());

		portletBagFactory.setServletContext(_servletContext);
		portletBagFactory.setWARFile(true);

		portletBagFactory.create(portletModel);

		List<Company> companies = _companyLocalService.getCompanies();

		deployPortlet(serviceReference, portletModel, companies);

		checkResources(serviceReference, portletModel, companies);

		portletModel.setReady(true);

		if (_log.isInfoEnabled()) {
			_log.info("Added " + serviceReference);
		}
	}

	protected com.liferay.portal.model.Portlet buildPortletModel(
		String portletId) {

		com.liferay.portal.model.Portlet portalPortletModel = null;

		try {
			portalPortletModel = _portletLocalService.getPortletById(
				CompanyConstants.SYSTEM, PortletKeys.PORTAL);
		}
		catch (SystemException se) {
			throw new RuntimeException(se);
		}

		com.liferay.portal.model.Portlet portletModel = new PortletImpl(
			CompanyConstants.SYSTEM, portletId);

		portletModel.setPluginPackage(portalPortletModel.getPluginPackage());
		portletModel.setPortletApp(portalPortletModel.getPortletApp());
		portletModel.setTimestamp(System.currentTimeMillis());

		return portletModel;
	}

	protected void checkResources(
			ServiceReference<Portlet> serviceReference,
			com.liferay.portal.model.Portlet portletModel,
			List<Company> companies)
		throws PortalException, SystemException {

		List<String> portletActions =
			_resourceActions.getPortletResourceActions(
				portletModel.getPortletId());

		_resourceActionLocalService.checkResourceActions(
			portletModel.getPortletId(), portletActions);

		List<String> modelNames = _resourceActions.getPortletModelResources(
			portletModel.getPortletId());

		for (String modelName : modelNames) {
			List<String> modelActions =
				_resourceActions.getModelResourceActions(modelName);

			_resourceActionLocalService.checkResourceActions(
				modelName, modelActions);
		}

		for (Company company : companies) {
			com.liferay.portal.model.Portlet companyPortletModel =
				_portletLocalService.getPortletById(
					company.getCompanyId(), portletModel.getPortletId());

			_portletLocalService.checkPortlet(companyPortletModel);
		}
	}

	protected void collectCacheScope(
		ServiceReference<Portlet> serviceReference,
		com.liferay.portal.model.Portlet portletModel) {
	}

	protected void collectExpirationCache(
		ServiceReference<Portlet> serviceReference,
		com.liferay.portal.model.Portlet portletModel) {

		Integer expirationCache = (Integer)serviceReference.getProperty(
			"javax.portlet.expiration-cache");

		portletModel.setExpCache(expirationCache);
	}

	protected void collectInitParams(
		ServiceReference<Portlet> serviceReference,
		com.liferay.portal.model.Portlet portletModel) {

		Map<String, String> initParams = new HashMap<String, String>();

		for (String initParamKey : serviceReference.getPropertyKeys()) {
			if (!initParamKey.startsWith("javax.portlet.init-param.")) {
				continue;
			}

			initParams.put(
				initParamKey.substring("javax.portlet.init-param.".length()),
				GetterUtil.getString(
					serviceReference.getProperty(initParamKey)));
		}

		portletModel.setInitParams(initParams);
	}

	protected void collectPortletInfo(
		ServiceReference<Portlet> serviceReference,
		com.liferay.portal.model.Portlet portletModel, String displayName) {

		String portletInfoTitle = (String)serviceReference.getProperty(
			"javax.portlet.info.title");

		if (portletInfoTitle == null) {
			portletInfoTitle = displayName;
		}

		String portletInfoShortTitle = (String)serviceReference.getProperty(
			"javax.portlet.info.short-title");
		String portletInfoKeyWords = (String)serviceReference.getProperty(
			"javax.portlet.info.keywords");
		String portletDescription = (String)serviceReference.getProperty(
			"javax.portlet.description");

		PortletInfo portletInfo = new PortletInfo(
			portletInfoTitle, portletInfoShortTitle, portletInfoKeyWords,
			portletDescription);

		portletModel.setPortletInfo(portletInfo);
	}

	protected void collectPortletModes(
		ServiceReference<Portlet> serviceReference,
		com.liferay.portal.model.Portlet portletModel) {

		Map<String, Set<String>> portletModes =
			new HashMap<String, Set<String>>();

		portletModes.put(
			ContentTypes.TEXT_HTML,
			SetUtil.fromArray(new String[] {toLowerCase(PortletMode.VIEW)}));

		List<String> portletModesStrings = StringPlus.asList(
			serviceReference.getProperty("javax.portlet.portletModes"));

		for (String portletModesString : portletModesStrings) {
			String[] portletModesStringParts = StringUtil.split(
				portletModesString, CharPool.SEMICOLON);

			if (portletModesStringParts.length != 2) {
				continue;
			}

			String mimeType = portletModesStringParts[0];

			Set<String> mimeTypePortletModes = new HashSet<String>();

			mimeTypePortletModes.add(toLowerCase(PortletMode.VIEW));
			mimeTypePortletModes.addAll(
				toLowerCaseSet(portletModesStringParts[1]));

			portletModes.put(mimeType, mimeTypePortletModes);
		}

		portletModel.setPortletModes(portletModes);
	}

	protected void collectPortletPreferences(
		ServiceReference<Portlet> serviceReference,
		com.liferay.portal.model.Portlet portletModel) {

		String defaultPreferences = (String)serviceReference.getProperty(
			"javax.portlet.preferences");

		if ((defaultPreferences != null) &&
			defaultPreferences.startsWith("classpath:")) {

			Bundle bundle = serviceReference.getBundle();

			URL url = bundle.getResource(
				defaultPreferences.substring("classpath:".length()));

			if (url != null) {
				try {
					defaultPreferences = StringUtil.read(url.openStream());
				}
				catch (IOException ioe) {
					_log.error(ioe, ioe);
				}
			}
		}

		portletModel.setDefaultPreferences(defaultPreferences);
	}

	protected void collectSecurityRoleRefs(
		ServiceReference<Portlet> serviceReference,
		com.liferay.portal.model.Portlet portletModel) {

		List<String> roleRefs = StringPlus.asList(
			serviceReference.getProperty("javax.portlet.security-role-ref"));

		Set<String> unlinkedRoles = new HashSet<String>(roleRefs);

		portletModel.setUnlinkedRoles(unlinkedRoles);
	}

	protected void collectWindowStates(
		ServiceReference<Portlet> serviceReference,
		com.liferay.portal.model.Portlet portletModel) {

		Map<String, Set<String>> windowStates =
			new HashMap<String, Set<String>>();

		windowStates.put(
			ContentTypes.TEXT_HTML,
			SetUtil.fromArray(
				new String[] {
					toLowerCase(LiferayWindowState.EXCLUSIVE),
					toLowerCase(LiferayWindowState.POP_UP),
					toLowerCase(WindowState.MAXIMIZED),
					toLowerCase(WindowState.MINIMIZED),
					toLowerCase(WindowState.NORMAL)
				}));

		List<String> windowStatesStrings = StringPlus.asList(
			serviceReference.getProperty("javax.portlet.windowStates"));

		for (String windowStatesString : windowStatesStrings) {
			String[] windowStatesStringParts = StringUtil.split(
				windowStatesString, CharPool.SEMICOLON);

			if (windowStatesStringParts.length != 2) {
				continue;
			}

			String mimeType = windowStatesStringParts[0];

			Set<String> mimeTypeWindowStates = new HashSet<String>();

			mimeTypeWindowStates.add(toLowerCase(WindowState.NORMAL));

			Set<String> windowStatesSet = toLowerCaseSet(
				windowStatesStringParts[1]);

			if (windowStatesSet.isEmpty()) {
				mimeTypeWindowStates.add(
					toLowerCase(LiferayWindowState.EXCLUSIVE));
				mimeTypeWindowStates.add(
					toLowerCase(LiferayWindowState.POP_UP));
				mimeTypeWindowStates.add(toLowerCase(WindowState.MAXIMIZED));
				mimeTypeWindowStates.add(toLowerCase(WindowState.MINIMIZED));
			}
			else {
				mimeTypeWindowStates.addAll(windowStatesSet);
			}

			windowStates.put(mimeType, mimeTypeWindowStates);
		}

		portletModel.setWindowStates(windowStates);
	}

	@Deactivate
	protected void deactivate() {
		_componentContext = null;

		_serviceTracker.close();

		_serviceTracker = null;

		if (_log.isInfoEnabled()) {
			_log.info("Deactivated");
		}
	}

	protected void deployPortlet(
			ServiceReference<Portlet> serviceReference,
			com.liferay.portal.model.Portlet portletModel,
			List<Company> companies)
		throws PortalException, SystemException {

		String categoryName = (String)serviceReference.getProperty(
			"com.liferay.portal.portlet.display.category");

		if (categoryName == null) {
			categoryName = "category.undefined";
		}

		for (Company company : companies) {
			com.liferay.portal.model.Portlet companyPortletModel =
				(com.liferay.portal.model.Portlet)portletModel.clone();

			companyPortletModel.setCompanyId(company.getCompanyId());

			_portletLocalService.deployRemotePortlet(
				companyPortletModel, categoryName);
		}
	}

	@Reference
	protected void setCompanyLocalService(
		CompanyLocalService companyLocalService) {

		_companyLocalService = companyLocalService;
	}

	@Reference
	protected void setPortletInstanceFactory(
		PortletInstanceFactory portletInstanceFactory) {

		_portletInstanceFactory = portletInstanceFactory;
	}

	@Reference
	protected void setPortletLocalService(
		PortletLocalService portletLocalService) {

		_portletLocalService = portletLocalService;
	}

	@Reference
	protected void setResourceActionLocalService(
		ResourceActionLocalService resourceActionLocalService) {

		_resourceActionLocalService = resourceActionLocalService;
	}

	@Reference
	protected void setResourceActions(ResourceActions resourceActions) {
		_resourceActions = resourceActions;
	}

	@Reference(
		target = "(original.bean=*)"
	)
	protected void setServletContext(ServletContext servletContext) {
		_servletContext = servletContext;
	}

	protected String toLowerCase(Object object) {
		String string = String.valueOf(object);

		return StringUtil.toLowerCase(string.trim());
	}

	protected Set<String> toLowerCaseSet(String string) {
		String[] array = StringUtil.split(string);

		for (int i = 0; i < array.length; i++) {
			array[i] = toLowerCase(array[i]);
		}

		return SetUtil.fromArray(array);
	}

	protected void unsetCompanyLocalService(
		CompanyLocalService companyLocalService) {

		_companyLocalService = null;
	}

	protected void unsetPortletInstanceFactory(
		PortletInstanceFactory portletInstanceFactory) {

		_portletInstanceFactory = null;
	}

	protected void unsetResourceActionLocalService(
		ResourceActionLocalService resourceActionLocalService) {

		_resourceActionLocalService = null;
	}

	protected void unsetResourceActions(ResourceActions resourceActions) {
		_resourceActions = null;
	}

	protected void unsetServletContext(ServletContext servletContext) {
		_servletContext = null;
	}

	private static final int _PORTLET_ID_MAX_LENGTH =
		255 - PortletConstants.INSTANCE_SEPARATOR.length() +
			PortletConstants.USER_SEPARATOR.length() + 39;

	private static Log _log = LogFactoryUtil.getLog(PortletTracker.class);

	private CompanyLocalService _companyLocalService;
	private ComponentContext _componentContext;
	private PortletInstanceFactory _portletInstanceFactory;
	private PortletLocalService _portletLocalService;
	private ResourceActionLocalService _resourceActionLocalService;
	private ResourceActions _resourceActions;
	private ServiceTracker<Portlet, Portlet> _serviceTracker;
	private ServletContext _servletContext;

}