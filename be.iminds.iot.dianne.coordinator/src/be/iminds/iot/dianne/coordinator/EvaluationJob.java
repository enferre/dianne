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
package be.iminds.iot.dianne.coordinator;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import be.iminds.iot.dianne.api.coordinator.EvaluationResult;
import be.iminds.iot.dianne.api.coordinator.Job.EvaluationCategory;
import be.iminds.iot.dianne.api.coordinator.Job.Type;
import be.iminds.iot.dianne.api.nn.eval.ClassificationEvaluation;
import be.iminds.iot.dianne.api.nn.eval.Evaluation;
import be.iminds.iot.dianne.api.nn.eval.EvaluationProgress;
import be.iminds.iot.dianne.api.nn.eval.Evaluator;
import be.iminds.iot.dianne.api.nn.module.dto.NeuralNetworkDTO;

public class EvaluationJob extends AbstractJob<EvaluationResult> {

	private EvaluationResult result = null;
	private Map<UUID, Evaluation> results = new HashMap<>();

	public EvaluationJob(DianneCoordinatorImpl coord,
			String dataset,
			Map<String, String> config,
			NeuralNetworkDTO[] nns){
		super(coord, Type.EVALUATE, dataset, config, nns);
		
		if(coord.datasets.isClassificationDataset(dataset) && !config.containsKey("criterion")){
			category = EvaluationCategory.CLASSIFICATION;
		} else {
			category = EvaluationCategory.CRITERION;
		}
	}
	
	@Override
	public void execute() throws JobFailedException {
		
		Map<String, String> evalConfig = new HashMap<>(config);
		if(!evalConfig.containsKey("range") && config.containsKey("testSet")){
			evalConfig.put("range", config.get("testSet"));
		}

		Thread[] threads = new Thread[targets.size()];
		for(int i=0;i<targets.size();i++){
			final UUID target = targets.get(i);
			threads[i] = new Thread(new Runnable(){
				public void run(){
					try {
						Evaluator evaluator = coordinator.evaluators.get(target);
						Evaluation e = evaluator.eval(dataset, evalConfig, nnis.get(target));
						
						System.out.println("Evaluation result");
						System.out.println("---");
						System.out.println("Metric: "+e.metric());
						System.out.println("Evaluation time: "+e.time());
						if(e instanceof ClassificationEvaluation){
							ClassificationEvaluation ce = (ClassificationEvaluation)e;
							System.out.println("Accuracy: "+ce.accuracy());
							try {
								System.out.println("Top-1 accuracy: "+ce.topNaccuracy(1));
								System.out.println("Top-3 accuracy: "+ce.topNaccuracy(3));
							} catch(Exception ex){
								// ignore if top accuracies are not applicable
							}
						}
						System.out.println("---");
						
						results.put(target, e);
					} catch(Exception e){
						done(new JobFailedException(target, EvaluationJob.this.jobId, "Evaluator failed on "+target+" : "+e.getMessage(), e));
					}
				}
			});
			threads[i].start();
		}
		
		for(Thread t : threads){
			try {
				t.join();
			} catch (InterruptedException e) {
			}
		}
		
		result = new EvaluationResult(results);
		
		done(result);
	}

	@Override
	public EvaluationResult getProgress() {
		if(result!=null)
			return result;
		
		Map<UUID, Evaluation> progresses = new HashMap<>();
		for(UUID target : targets){
			EvaluationProgress p = null; 
			if(results.containsKey(target)){
				Evaluation eval = results.get(target);
				p = new EvaluationProgress(eval.size(), eval.size(), eval.metric);
			} else {
				Evaluator evaluator = coordinator.evaluators.get(target);
				p = evaluator.getProgress();
			}
			progresses.put(target, p);
		}
		return new EvaluationResult(progresses);
	}

}
