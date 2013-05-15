/*
 * Copyright (c) 2011-2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.spring.context;

import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.convert.ConversionService;
import reactor.core.R;
import reactor.core.Reactor;
import reactor.fn.dispatch.Dispatcher;

/**
 * @author Jon Brisbin
 * @author Stephane Maldini
 */
public class ReactorFactoryBean implements FactoryBean<Reactor> {

	private static final Reactor ROOT_REACTOR = new Reactor();

	static {
		R.link(ROOT_REACTOR);
	}

	@Autowired(required = false)
	private ConversionService conversionService;
	private boolean rootReactor = false;
	private String     name;
	private Dispatcher dispatcher;

	public ReactorFactoryBean(boolean rootReactor) {
		this.rootReactor = rootReactor;
	}

	public ReactorFactoryBean() {
	}

	public ConversionService getConversionService() {
		return conversionService;
	}

	public ReactorFactoryBean setConversionService(ConversionService conversionService) {
		this.conversionService = conversionService;
		return this;
	}

	public boolean isRootReactor() {
		return rootReactor;
	}

	public ReactorFactoryBean setRootReactor(boolean rootReactor) {
		this.rootReactor = rootReactor;
		return this;
	}

	public String getName() {
		return name;
	}

	public ReactorFactoryBean setName(String name) {
		this.name = name;
		return this;
	}

	public Dispatcher getDispatcher() {
		return dispatcher;
	}

	public ReactorFactoryBean setDispatcher(Dispatcher dispatcher) {
		this.dispatcher = dispatcher;
		if (ROOT_REACTOR.getDispatcher() != dispatcher) {
			ROOT_REACTOR.setDispatcher(dispatcher);
		}
		return this;
	}

	@Override
	public Reactor getObject() throws Exception {
		Reactor reactor;
		if (rootReactor) {
			reactor = ROOT_REACTOR;
		} else if (null != name) {
			reactor = R.createOrGet(name);
		} else {
			reactor = R.create();
		}

		if (conversionService != null) {
			reactor.setConverter(new ConversionServiceConverter(conversionService));
		}

		if (dispatcher != null) {
			reactor.setDispatcher(dispatcher);
		}

		return reactor;
	}

	@Override
	public Class<?> getObjectType() {
		return Reactor.class;
	}

	@Override
	public boolean isSingleton() {
		return rootReactor || null != name;
	}

}
