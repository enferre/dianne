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
package be.iminds.iot.dianne.rl.experience;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import be.iminds.iot.dianne.api.dataset.AbstractDataset;
import be.iminds.iot.dianne.api.dataset.Sequence;
import be.iminds.iot.dianne.api.rl.dataset.BatchedExperiencePoolSequence;
import be.iminds.iot.dianne.api.rl.dataset.ExperiencePool;
import be.iminds.iot.dianne.api.rl.dataset.ExperiencePoolBatch;
import be.iminds.iot.dianne.api.rl.dataset.ExperiencePoolSample;
import be.iminds.iot.dianne.api.rl.dataset.ExperiencePoolSequence;
import be.iminds.iot.dianne.tensor.Tensor;

public abstract class AbstractExperiencePool extends AbstractDataset implements ExperiencePool {

	protected int maxSize = 10000; // max number of samples in the experience pool
	
	protected int[] stateDims;
	protected int stateSize;
	protected int[] actionDims;
	protected int actionSize;
	protected int sampleSize;
	
	protected boolean infiniteHorizon = false;
	
	protected ExecutorService queuedAddSequenceThread = Executors.newSingleThreadExecutor();
	
	public class SequenceLocation {
		public final int start;
		public final int length;
		public final boolean infinite;
		
		public SequenceLocation(int start, int length){
			this.start = start;
			this.length = length;
			this.infinite = false;
		}
		
		public SequenceLocation(int start, int length, boolean infinite){
			this.start = start;
			this.length = length;
			this.infinite = infinite;
		}
		
		public String toString(){
			return "Sequence start: "+start+" length: "+length+" infinite: "+infinite;
		}
	}
	
	protected List<SequenceLocation> sequences = new ArrayList<>();
	
	protected ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
	
	@Override
	public void activate(Map<String, Object> config) {
		super.activate(config);
		
		stateDims = inputDims;
		stateSize = inputSize;
	
		actionDims = targetDims;
		actionSize = targetSize;
	
		sampleSize = stateSize+actionSize+2;

		setup(config);
		
		recover();
	}
	
	@Override
	protected void init(Map<String, Object> properties) {
		if(properties.containsKey("maxSize"))
			this.maxSize = Integer.parseInt(properties.get("maxSize").toString().trim());
		
		String[] id = (String[])properties.get("stateDims");
		if(id!=null){
			inputDims= new int[id.length];
			for(int i=0;i<id.length;i++){
				inputDims[i] = Integer.parseInt(id[i].trim());
			}
		}
		
		inputType = (String)properties.get("stateType");
		
		String[] od = (String[])properties.get("actionDims");
		if(od!=null){
			targetDims= new int[od.length];
			for(int i=0;i<od.length;i++){
				targetDims[i] = Integer.parseInt(od[i].trim());
			}
		}
		
		targetType = (String)properties.get("actionType");
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public String[] getLabels() {
		return labels;
	}

	@Override
	public int size() {
		return noSamples;
	}
	
	@Override
	public int sequences(){
		return sequences.size();
	}
	
	@Override
	public int sequenceLength(int sequence){
		return sequences.get(sequence).length;
	}
	
	@Override
	protected void readLabels(String labelsFile) {}
	
	@Override
	protected Tensor getInputSample(Tensor t, int index) {
		ExperiencePoolSample s = getSample(null, index);
		return s.input.copyInto(t);
	}

	@Override
	protected Tensor getTargetSample(Tensor t, int index) {
		ExperiencePoolSample s = getSample(null, index);
		return s.target.copyInto(t);
	}
	
	@Override
	public ExperiencePoolSample getSample(ExperiencePoolSample s, int index){
		// no locking since that kills performance and every part of the experience pool should be a valid xp sample
		return getSample(s, index, 0, true);
	}
		
	@Override
	public ExperiencePoolBatch getBatch(ExperiencePoolBatch b, int... indices) {
		if(b == null){
			b = new ExperiencePoolBatch(indices.length, stateDims, actionDims);
		}
		
		int i = 0;
		for(int index : indices){
			getSample(b.getSample(i++), index, 0, true);
		}
		
		return b;
	}

	
	@Override
	public ExperiencePoolSequence getSequence(ExperiencePoolSequence s, int sequence, int index, int length){
		if(s == null){
			s = new ExperiencePoolSequence();
		}
		List<ExperiencePoolSample> list = s.data; 
				
		if(sequence > sequences.size()){
			throw new RuntimeException("Invalid sequence number");
		}
		
		try {
			lock.readLock().lock();
			
			SequenceLocation seq = sequences.get(sequence);
			if(index >= seq.length){
				throw new RuntimeException("Invalid start index: "+index);
			}
			
			if(length == -1){
				length = seq.length-index;
			} else {
				length = seq.length-index < length ? seq.length-index : length;
			}
			
			ExperiencePoolSample previous = null;
			for(int i=0;i<length;i++){
				ExperiencePoolSample sample = null;
				if(list.size() <= i){
					sample = new ExperiencePoolSample(previous == null ? null : previous.nextState, null, 0, null);
					list.add(sample);
				} else {
					sample = list.get(i);
					if(previous != null){
						sample.input = previous.nextState;
					}
				}
				
				if(i==0){
					getSample(sample, i+index, sequence, true);
				} else {
					getSample(sample, i+index, sequence, false);
				}
				
				previous = sample;
			}
			
			s.size = length;
		} finally {
			lock.readLock().unlock();
		}

		return s;
	}
	
	@Override
	public BatchedExperiencePoolSequence getBatchedSequence(BatchedExperiencePoolSequence b, final int[] sequences, final int[] indices, final int length){
		if(b == null){
			b = new BatchedExperiencePoolSequence();
		}
		List<ExperiencePoolBatch> list = b.data; 
				
		boolean l = false;
		for(int sequence : sequences){
			if(sequence > this.sequences.size()){
				throw new RuntimeException("Invalid sequence number");
			}
			if(sequence == 0){
				l = true;
			}
		}
		
		try {
			// only lock if you want the first sequence ... this is the only one that might get overridden when no lock taken
			if(l){
				lock.readLock().lock();
			}
			
			int[] globalIndices = new int[sequences.length];
			int minLength = Integer.MAX_VALUE;
			
			for(int k=0; k<sequences.length;k++){
				SequenceLocation seq = this.sequences.get(sequences[k]);
				if(indices[k] >= seq.length){
					throw new RuntimeException("Invalid start index: "+indices[k]);
				}
			
				int sequenceLength;
				if(length == -1){
					sequenceLength = seq.length-indices[k];
				} else {
					sequenceLength = seq.length-indices[k] < length ? seq.length-indices[k] : length;
				}
				
				if(sequenceLength < minLength){
					minLength = sequenceLength;
				}
			
				globalIndices[k] = seq.start + indices[k];
			}
				
			
			for(int i=0;i<minLength;i++){
				if(list.size() > i){
					getBatch(list.get(i), globalIndices);
				} else {
					ExperiencePoolBatch batch = getBatch(null, globalIndices);
					list.add(batch);
				}
				
				for(int k=0;k<globalIndices.length;k++){
					globalIndices[k] = globalIndices[k]+1;
				}
			}
			
			b.size = minLength;
		} finally {
			if(l){
				lock.readLock().unlock();
			}
		}

		return b;
	}
	
	
	@Override
	public void addSequence(Sequence<ExperiencePoolSample> sequence){
		if(sequence == null){
			throw new RuntimeException("Null sequence given");
		}

		int length = sequence.size();
		int size = length;
		boolean infinite = false;
		if(!sequence.get(size-1).isTerminal()){
			size += 1;
			infinite = true;
			infiniteHorizon = true;
		}
		
		if(size > maxSize){
			// this cannot be stored in this pool
			System.out.println("Warning, a sequence of length "+size+" cannot be stored in this pool");
			return;
		}
		
		float[] buffer = new float[sampleSize];
		boolean locked = false;
		try {
			locked = lock.writeLock().tryLock();
			
			if(!locked){
				// take copy of the sequence and add to the queuedAddSequence thread...
				// this allows for agents not having to keep on waiting until sequence is added...
				final Sequence<ExperiencePoolSample> copy = sequence.clone();
				
				// TODO should we make sure we are not getting flooded with addSequences 
				// and ignore addSequence calls once queue gets crowded?
				queuedAddSequenceThread.submit(() ->{
					try {
						lock.writeLock().lock();
						addSequence(copy);
					} finally {
						lock.writeLock().unlock();
					}
				});
				return;
			}

			int index = getBufferEnd();
			int start = index == maxSize ? 0 : index;
			
			if(start == 0 && !sequences.isEmpty()
					&& sequences.get(0).start == 0){
				SequenceLocation removed = sequences.remove(0);
				noSamples -= removed.length;
			}
			
			for(int i=0;i<size;i++){
				Arrays.fill(buffer, 0.0f);
				if(sequence.size() == i){
					ExperiencePoolSample s = sequence.get(sequence.size()-1);
					System.arraycopy(s.nextState.get(), 0, buffer, 0 , stateSize);
					buffer[stateSize+actionSize+1] = 1.0f;
				} else {
					ExperiencePoolSample s = sequence.get(i);
					System.arraycopy(s.input.get(), 0, buffer, 0 , stateSize);
					System.arraycopy(s.target.get(), 0, buffer, stateSize, actionSize);
					buffer[stateSize+actionSize] = s.getScalarReward();
					buffer[stateSize+actionSize+1] = s.isTerminal() ? 0.0f : 1.0f;
				}
				
				if(index == maxSize){
					// cycle 
					index = 0;
					
					if(sequences.get(0).start == 0){
						SequenceLocation removed = sequences.remove(0);
						noSamples -= removed.length;
					}
				}

				if( getBufferStart() > 0 && index == getBufferStart()){
					SequenceLocation removed = sequences.remove(0);
					noSamples -= removed.length;
				}
				
				writeData((long)index*(long)sampleSize, buffer);
				index++;
			}
			
			SequenceLocation seq = new SequenceLocation(start, length, infinite);
			sequences.add(seq);
			noSamples+= length;
			
		} catch(Throwable t){ 
			t.printStackTrace();
		} finally {
			if(locked)
				lock.writeLock().unlock();
		}
	}
	
	@Override
	public void reset() {
		try {
			lock.writeLock().lock();
			noSamples = 0;
			sequences.clear();
		} finally {
			lock.writeLock().unlock();
		}
		
	}

	protected ExperiencePoolSample getSample(ExperiencePoolSample s, int index, int startSequence, boolean loadState){
		float[] sampleBuffer = new float[sampleSize];
		
		long bufferPosition = 0;
		try {
			lock.readLock().lock();
			bufferPosition = getBufferPosition(index, startSequence);
			loadData(bufferPosition*sampleSize, sampleBuffer);
		} finally {
			lock.readLock().unlock();
		}

		
		if(s == null){
			s = new ExperiencePoolSample();	
		}
		
		// state
		// in case of sequences on might just reuse nextState tensor of previous instead of loading state again
		if(loadState){
			float[] stateBuffer = new float[stateSize];
			System.arraycopy(sampleBuffer, 0, stateBuffer, 0, stateSize);

			if(s.input == null){
				s.input = new Tensor(stateBuffer, stateDims);
			} else {
				s.input.set(stateBuffer);
			}
		}
		
		// action
		float[] actionBuffer = new float[actionSize];
		System.arraycopy(sampleBuffer, stateSize, actionBuffer, 0, actionSize);
		
		if(s.target == null){
			s.target = new Tensor(actionBuffer, actionDims);
		} else {
			s.target.set(actionBuffer);
		}
		
		// reward
		if(s.reward == null){
			s.reward = new Tensor(1);
		}
		s.reward.set(sampleBuffer[stateSize+actionSize], 0);
		
		// terminal
		if(s.terminal == null){
			s.terminal = new Tensor(1);
		}
		s.terminal.set(sampleBuffer[stateSize+actionSize+1], 0);
		
		if(!s.isTerminal()){
			// load next state
			float[] nextStateBuffer = new float[stateSize];
			bufferPosition += 1;
			if(bufferPosition == maxSize)
				bufferPosition = 0;
			
			loadData((bufferPosition*sampleSize) , nextStateBuffer);

			if(s.nextState == null){
				s.nextState = new Tensor(nextStateBuffer, stateDims);
			} else {
				s.nextState.set(nextStateBuffer);
			}
		} else {
			if(s.nextState == null){
				s.nextState = new Tensor(stateDims);
			} 
			s.nextState.fill(0);
		}
		
		return s;
	}
	
	private long getBufferPosition(long index, int startSequence){
		long pos;
		long start = sequences.get(startSequence).start;
		if(!infiniteHorizon){
			pos = (start+index) % maxSize;
		} else {
			int i = startSequence;
			long trackedIndex = 0;
			long correctedIndex = 0;
			while(i < sequences.size() && trackedIndex < index){
				int length = sequences.get(i).length;
				trackedIndex += length;
				correctedIndex += length;
				if(trackedIndex > index){
					correctedIndex -= (trackedIndex-index);
				} else if(sequences.get(i).infinite){
					correctedIndex +=1;
				}
				i++;
			}
			pos = (start+correctedIndex) % maxSize;
		}
		return pos;
	}
	
	private int getBufferStart(){
		if(sequences.isEmpty())
			return 0;
		return sequences.get(0).start;
	}
	
	private int getBufferEnd(){
		if(sequences.isEmpty())
			return 0;
		SequenceLocation last = sequences.get(sequences.size() - 1);
		return  (last.start+last.length + (last.infinite ? 1 : 0)) % maxSize;
	}
	
	protected abstract void setup(Map<String, Object> config);
	
	protected abstract void loadData(long position, float[] data);
	
	protected abstract void writeData(long position, float[] data);

	protected abstract void dumpData() throws IOException;
	
	protected abstract void recoverData();
	
	@Override
	public void dump() {
		// write json if not preset
		StringBuilder descriptor = new StringBuilder();
		
		descriptor.append("{\n\t");
		descriptor.append("\"name\":");
		descriptor.append("\"").append(name).append("\",\n\t");
		descriptor.append("\"type\":");
		descriptor.append("\"").append(this.getClass().getSimpleName()).append("\",\n\t");
		descriptor.append("\"stateDims\":");
		descriptor.append(Arrays.toString(stateDims)).append(",\n\t");
		if(inputType!=null){
			descriptor.append("\"stateType\":");
			descriptor.append(inputType).append(",\n\t");
		}
		descriptor.append("\"actionDims\":");
		descriptor.append(Arrays.toString(actionDims)).append(",\n\t");
		if(targetType!=null){
			descriptor.append("\"actionType\":");
			descriptor.append(targetType).append(",\n\t");
		}
		descriptor.append("\"maxSize\":");
		descriptor.append(maxSize);
		descriptor.append("\n}");
		
		try {
			Files.write( Paths.get(dir+File.separator+name+".json"), descriptor.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		
			lock.writeLock().lock();
		
			// write sequences
			try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(new File(dir+File.separator+"sequences"))))){
				for(SequenceLocation s : sequences){
					out.writeInt(s.start);
					out.writeInt(s.length);
					out.writeBoolean(s.infinite);
				}
				out.flush();
			}
			
			// dump data
			dumpData();
		
		} catch(Exception e){ 
			e.printStackTrace();
		}finally {
			lock.writeLock().unlock();
		}
	}
	
	public void recover() {
		try {
			lock.writeLock().lock();

			// recover sequences
			try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(new File(dir+File.separator+"sequences"))))){
				while(true){
					int start = in.readInt();
					int length = in.readInt();
					boolean infinite = in.readBoolean();
					if(infinite)
						infiniteHorizon = true;
					
					SequenceLocation s = new SequenceLocation(start, length, infinite);
					sequences.add(s);
		
					noSamples+=length;
				}
			} catch(Exception e){
			}
			
			// recover data
			recoverData();
			
		} finally {
			lock.writeLock().unlock();
		}
		
	}
}
