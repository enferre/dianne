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
package be.iminds.iot.dianne.things.output;

import java.util.Hashtable;
import java.util.UUID;

import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.util.promise.Promise;

import be.iminds.iot.dianne.api.nn.module.ModuleException;
import be.iminds.iot.dianne.tensor.ModuleOps;
import be.iminds.iot.dianne.tensor.Tensor;
import be.iminds.iot.dianne.tensor.TensorOps;
import be.iminds.iot.input.joystick.api.JoystickEvent;
import be.iminds.iot.input.joystick.api.JoystickEvent.JoystickButton;
import be.iminds.iot.input.joystick.api.JoystickListener;
import be.iminds.iot.input.keyboard.api.KeyboardEvent;
import be.iminds.iot.input.keyboard.api.KeyboardListener;
import be.iminds.iot.robot.api.arm.Arm;
import be.iminds.iot.robot.api.omni.OmniDirectional;
import be.iminds.iot.sensor.api.LaserScanner;
import be.iminds.iot.sensor.api.SensorValue;

public class YoubotOutput extends ThingOutput implements JoystickListener, KeyboardListener {
	
	private enum Mode {
		IGNORE,
		DISCRETE,
		DISCRETE_SOFTMAX,
		CONTINUOUS,
		STOCHASTIC,
		ANY
	}

	private BundleContext context;
	
	private OmniDirectional base;
	private Arm arm;
	private LaserScanner laserScanner;
	
	private float speed = 0.1f;
	private float discreteGripThreshold = 0.8f;
	private float continuousGripThreshold = 0.2f;
	private float ignoreGripThreshold = -0.02f;
	private boolean fixedGrip = false;
	
	private float vx = 0;
	private float vy = 0;
	private float va = 0;
	private boolean grip = false;

	private Tensor sample = new Tensor(3);
	
	private volatile boolean skip = false;
	private volatile boolean stop = false;
	
	private ServiceRegistration registration;
	
	private Mode mode = Mode.ANY;
	
	public YoubotOutput(UUID id, String name){
		super(id, name, "Youbot");
	}
	
	public void setBase(OmniDirectional b){
		this.base = b;
	}
	
	public void setArm(Arm a){
		this.arm = a;
	}
	
	@Override
	public void onForward(final UUID moduleId, final Tensor output, final String... tags) {
		if(output.dim() != 1){
			System.out.println("Wrong output dimensions");
			return;
		}
		
		if(mode == Mode.IGNORE){
			return;
		}
		
		if(skip || stop){
			return;
		}
		
		// TODO this code is replicated from the Environment to have same behavior
		// Should this somehow be merged together?
		int outputs = output.size(0);
		if(outputs == 7 && (mode == Mode.DISCRETE || mode == Mode.DISCRETE_SOFTMAX || mode == Mode.ANY)){
			// treat as discrete outputs
			int action = TensorOps.argmax(output);
			
			float sum = TensorOps.sum(TensorOps.exp(null, output));
			Tensor narrowed = output.narrow(0, 6);
			if(Math.abs(1.0f - sum) < 0.001){
				if(mode == Mode.DISCRETE)
					return;
				
				// coming from logsoftmax policy (should we sample?)
				if(action == 6 && output.get(6) < ignoreGripThreshold){
					action = TensorOps.argmax(narrowed);
				}
			} else {
				// DQN network, values are Q values
				if(mode == Mode.DISCRETE_SOFTMAX){
					return;
				}

				// if gripThreshold specified, use that one instead of grip Q value (which is hard to train)
				if(discreteGripThreshold > 0){
					if(TensorOps.dot(narrowed, narrowed) >= discreteGripThreshold){
						action = TensorOps.argmax(narrowed);
					}
				}
			}
			
			grip = false;
			switch(action){
			case 0:
				vx = 0;
				vy = speed;
				va = 0;
				break;
			case 1:
				vx = 0;
				vy = -speed;
				va = 0;
				break;
			case 2:
				vx = speed;
				vy = 0;
				va = 0;
				break;
			case 3:
				vx = -speed;
				vy = 0;
				va = 0;
				break;
			case 4:
				vx = 0;
				vy = 0;
				va = 2*speed;
				break;
			case 5:
				vx = 0;
				vy = 0;
				va = -2*speed;
				break;	
			case 6:
				grip = true;
			}
			
		} else if(outputs == 3 && (mode == Mode.CONTINUOUS || mode == Mode.ANY)) {
			float[] action = output.get();
			// treat as continuous outputs
			if(TensorOps.dot(output, output) < continuousGripThreshold){
				// grip	
				grip = true;
			} else {
				// move
				grip = false;
				vx = action[0]*speed;
				vy = action[1]*speed;
				va = action[2]*speed*2;
			}
		} else if(outputs == 6 && (mode == Mode.STOCHASTIC || mode == Mode.ANY)) {
			sample.randn();
			
			TensorOps.cmul(sample, sample, output.narrow(0, 3, 3));
			TensorOps.add(sample, sample, output.narrow(0, 0, 3));
		
			float[] action = sample.get();
			// treat as continuous outputs
			if(TensorOps.dot(sample, sample) < continuousGripThreshold){
				// grip	
				grip = true;
			} else {
				// move
				grip = false;
				vx = action[0]*speed;
				vy = action[1]*speed;
				va = action[2]*speed*2;
			}
		} else {
			grip = false;
			vx = 0;
			vy = 0;
			va = 0;
		}
		
		if(grip){
			if(!fixedGrip){
				gripCustom();
			} else {
				gripFixed();
			}
		} else {
			base.move(vx, vy, va);
		}
	}

	@Override
	public void onError(UUID moduleId, ModuleException e, String... tags) {
	}

	@Override
	public void connect(UUID nnId, UUID outputId, BundleContext context){
		if(this.context == null)
			activate(context);
		
		if(!isConnected()){
			stop = false;
			registration = context.registerService(new String[]{JoystickListener.class.getName(),KeyboardListener.class.getName()}, this, new Hashtable<>());
			
			try {
				hover().getValue();
			} catch (Exception e) {
			}
		}
		
		super.connect(nnId, outputId, context);
	}
	
	@Override
	public void disconnect(UUID nnId, UUID outputId){
		// stop youbot on disconnect
		super.disconnect(nnId, outputId);
		
		if(!isConnected()){
			stop = true;

			base.stop();
			// go to candle and reset
			arm.setPositions(2.92510465f, 1.103709733f, -2.478948503f, 1.72566195f)
					.then(p -> arm.reset());
			
			registration.unregister();
		}
	}

	@Override
	public void onEvent(JoystickEvent e) {
		switch(e.type){
		case BUTTON_X_PRESSED:
			mode = Mode.IGNORE;
			base.stop();
			System.out.println("Ignore any neural net robot control signals");
			break;
		case BUTTON_Y_PRESSED:
			if(e.isPressed(JoystickButton.BUTTON_R1)){
				mode = Mode.DISCRETE_SOFTMAX;
				System.out.println("Accept only discrete softmax neural net robot control signals");
			} else {
				mode = Mode.DISCRETE;
				System.out.println("Accept only discrete neural net robot control signals");
			}
			break;
		case BUTTON_A_PRESSED:
			mode = Mode.ANY;
			System.out.println("Accept any robot control signals");
			break;	
		case BUTTON_B_PRESSED:
			if(e.isPressed(JoystickButton.BUTTON_R1)){
				mode = Mode.STOCHASTIC;
				System.out.println("Accept only stochastic continuous neural net robot control signals");
			} else {
				mode = Mode.CONTINUOUS;
				System.out.println("Accept only continous neural net robot control signals");
			}
			break;
		default:
		}
	}

	@Override
	public void onEvent(KeyboardEvent e) {
		if(e.type!=KeyboardEvent.Type.PRESSED)
			return;
		
		switch(e.key){
		case "1":
			mode = Mode.IGNORE;
			base.stop();
			System.out.println("Ignore any neural net robot control signals");
			break;
		case "2":
			mode = Mode.DISCRETE;
			System.out.println("Accept only discrete neural net robot control signals");
			break;
		case "3":
			mode = Mode.DISCRETE_SOFTMAX;
			System.out.println("Accept only discrete softmax neural net robot control signals");
			break;	
		case "4":
			mode = Mode.CONTINUOUS;
			System.out.println("Accept only continous neural net robot control signals");
			break;
		case "5":
			mode = Mode.STOCHASTIC;
			System.out.println("Accept only stochastic continuous neural net robot control signals");
			break;	
		case "0":
			mode = Mode.ANY;
			System.out.println("Accept any robot control signals");
			break;
		default:
			break;
		}
	}
	
	
	private void gripFixed(){
		skip = true;
		base.stop();	
		arm.openGripper()
				.then(p -> arm.setPositions(2.92f, 0.0f, 0.0f, 0.0f, 2.875f))
				.then(p -> arm.setPositions(2.92f, 1.76f, -1.37f, 2.55f))
				.then(p -> arm.closeGripper())
				.then(p -> arm.setPositions(0.01f, 0.8f))
				.then(p -> arm.setPositions(0.01f, 0.8f, -1f, 2.9f))
				.then(p -> arm.openGripper())
				.then(p -> arm.setPosition(1, -1.3f))
				.then(p -> {skip = false; return arm.setPositions(2.92f, 0.0f, 0.0f, 0.0f, 2.875f);});
	}

	private void gripCustom(){
		skip = true;
		base.stop();
		
		final float x,y;
		if(laserScanner == null){
			try {
				ServiceReference[] refs = context.getServiceReferences(LaserScanner.class.getName(), "(|(name=Hokuyo)(name=hokuyo))");
				if(refs != null){
					laserScanner = (LaserScanner) context.getService(refs[0]);
				}
			} catch (Exception e) {
			}
		}
		
		if(laserScanner != null){
			int size = laserScanner.getValue().data.length;
			double step = Math.PI/size;
			
			int count = 0;
			int kw = 3;
			int pad = 1;
			
			float distance = 0;
			double alfa = 0;
			Tensor ind = new Tensor(1, 1, size);
			Tensor pooled = new Tensor(1, 1, size);
			
			for(int i=0;i<5;i++) {
				SensorValue v = laserScanner.getValue();
				Tensor laserScan = new Tensor(v.data, size);
				laserScan.reshape(1, 1, size);
				// pool to filter out single 0 values
				pooled = ModuleOps.spatialmaxpool(pooled, laserScan, ind, kw, 1, 1, 1, pad, 0);

				// TODO check whether there is actually a can to grip, ignore otherwise?
				
				float d =  TensorOps.min(pooled);
				double a = (TensorOps.argmin(pooled))*step; 

				if(d > 0.1){
					distance += d;
					alfa += a;
					count++;
				} else {
					// make the maxpool broader?
					kw+=2;
					pad+=1;
				}
				
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {}
			}
			
			distance /= count;
			alfa /= count;
			
			if(distance > 0.1 ){
				x = 0.22f + (float) (distance * Math.sin(alfa));
				y = (float)(-distance * Math.cos(alfa));
			} else {
				x = 0.44f;
				y = 0;
			}
			System.out.println("Grip at d: " +distance+" a: "+alfa+" x: "+x+" y: "+y);
		} else {
			x = 0.4f;
			y = 0f;
		}

		arm.moveTo(x, y, 0.15f)
			.then(p -> arm.moveTo(x, y, 0.085f), p -> hover())
			.then(p -> arm.closeGripper(), p -> hover())
			.then(p -> arm.setPositions(0.01f, 0.8f), p -> hover())
			.then(p -> arm.setPositions(0.01f, 0.8f, -1f, 2.9f), p -> hover())
			.then(p -> arm.openGripper(), p -> hover())
			.then(p -> arm.setPosition(1, -1.3f), p -> hover())
			.then(p -> hover());
	}
	
	private Promise<Arm> hover(){
		skip = false;
		arm.openGripper();
		return arm.setPosition(4, 2.9f).then(p ->  arm.moveTo(0.4f, 0f, 0.45f));
	}
	
	private void activate(BundleContext context){
		this.context = context;
		
		String s = context.getProperty("be.iminds.iot.dianne.youbot.speed");
		if(s!=null){
			speed = Float.parseFloat(s);
		}
		
		s = context.getProperty("be.iminds.iot.dianne.youbot.gripThreshold.discrete");
		if(s!=null){
			discreteGripThreshold = Float.parseFloat(s);
		}
	
		s = context.getProperty("be.iminds.iot.dianne.youbot.gripThreshold.continuous");
		if(s!=null){
			continuousGripThreshold = Float.parseFloat(s);
		}
		
		s = context.getProperty("be.iminds.iot.dianne.youbot.ignoreGripThreshold");
		if(s!=null){
			ignoreGripThreshold = Float.parseFloat(s);
		}

		s = context.getProperty("be.iminds.iot.dianne.youbot.fixedGrip");
		if(s!=null){
			fixedGrip = Boolean.parseBoolean(s);
		}
	}
}
