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
package be.iminds.iot.dianne.rl.learn.strategy.config;

import be.iminds.iot.dianne.nn.learn.criterion.CriterionFactory.CriterionConfig;
import be.iminds.iot.dianne.nn.learn.processors.ProcessorFactory.ProcessorConfig;
import be.iminds.iot.dianne.nn.learn.sampling.SamplingFactory.SamplingConfig;

public class StateBeliefConfig {


	public int stateSize = 100;
	
	public int sequenceLength = 10;
	
	public int batchSize = 32;
	
	public float priorRegularization = 0;
	
	public float dropRate = 0;
	
	public boolean reconstructOnDrop = true;
	
	/**
	 * The criterion to use to evaluate the loss between output and target
	 */
	public CriterionConfig criterion = CriterionConfig.GAU;
	
	/**
	 * The gradient optimization method to use
	 *  * SGD - stochastic gradient descent (optionally with (nesterov) momentum and regularization parameters)
	 *  * Adadelta
	 *  * Adagrad
	 *  * RMSprop
	 */
	public ProcessorConfig method = ProcessorConfig.SGD;
	
	/**
	 * The sampling strategy to use to traverse the dataset
	 *  * Random
	 *  * Sequential
	 */
	public SamplingConfig sampling = SamplingConfig.UNIFORM;
	
	public float quantizeMin = 0;
	public float quantizeMax = 4;
	public int quantizeSteps = 400;
}
