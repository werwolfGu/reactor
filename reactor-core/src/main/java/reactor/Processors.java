/*
 * Copyright (c) 2011-2015 Pivotal Software Inc, All Rights Reserved.
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
package reactor;

import org.reactivestreams.Processor;

/**
 * @author Stephane Maldini
 * @since 2.1
 */
public final class Processors {

	/**
	 * Default number of processors available to the runtime on init (min 2)
	 *
	 * @see Runtime#availableProcessors()
	 */
	public static final int DEFAULT_POOL_SIZE = Math.min(Runtime.getRuntime().availableProcessors(), 2);

	public <I, O> Processor<I, O> broadcast(Processor<I, O> processor) {
		processor.onSubscribe(Publishers.NOOP_SUBSCRIPTION);
		return processor;
	}

}
