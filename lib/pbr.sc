PSOLABufRead {
	*ar { |sampleBuf, pitchBuf, phase, controlPhase, rate, targetPitch, formantRatio, formantRatioTrack=0.15, periodsPerGrain = 2, timeDispersion, speed=0.05, ratioDeviationMult = 1|

		var out, grainDur, wavePeriod, trigger, grainFreq, grainPos;
		var minFreq = 10;
		var pitchPhase = controlPhase;
		var pitchInfo = BufRd.kr(2, pitchBuf, pitchPhase);
		var freq = pitchInfo[0];
		var hasFreq = pitchInfo[1];
		var instantPitchRatio = targetPitch/freq;
		var pitchRatio = (instantPitchRatio).lag(speed);
		var pitchRatioDeviation = pitchRatio - instantPitchRatio;
		var secondaryFormantRatio = Sanitize.kr(pitchRatio**formantRatioTrack, 1);
		pitchRatio = instantPitchRatio + (ratioDeviationMult*pitchRatioDeviation);
		freq = freq.max(minFreq);
		grainFreq = pitchRatio*freq;
		grainDur = periodsPerGrain * freq.reciprocal;
		if (formantRatio.isNil, {
			formantRatio = 1
		});
		formantRatio = (formantRatio*secondaryFormantRatio).clip(0.1, 10);
		grainPos = (phase - Phasor.ar(0, rate, 0, freq.reciprocal * SampleRate.ir));
		grainPos = grainPos - (SampleRate.ir*grainDur*(formantRatio - 1).max(0));
		grainPos = grainPos.wrap(0, BufFrames.kr(sampleBuf));


		if(timeDispersion.isNil, {
			trigger = Impulse.ar(grainFreq);
		}, {
			trigger = Impulse.ar(grainFreq + (LFNoise0.kr(grainFreq) * timeDispersion));
		});
		out = GrainBuf.ar(1, trigger, grainDur, sampleBuf, formantRatio, (grainPos/BufFrames.kr(sampleBuf)).wrap(0, 1));
		^out;
	}
}

PitchedGrainBufRead {
	*ar { |sampleBuf, pitchBuf, phase, controlPhase, rate, targetPitch, length, overlap, irregular, detune, ratioDeviationMult = 1|

		var out, grainDur, wavePeriod, trigger, grainFreq, grainPos;
		var minFreq = 10;
		var grainTrigs = Impulse.kr(overlap/length, phase: irregular*LFNoise1.kr(overlap/length).unipolar);
		var pitchPhase = controlPhase;
		var pitchInfo = BufRd.kr(2, pitchBuf, pitchPhase);
		var freq = Sanitize.kr(pitchInfo[0], 110);
		var hasFreq = pitchInfo[1];
		var pitchRatio = (targetPitch/freq)*(1+(detune*LFNoise0.kr(2*overlap/length)));
		grainPos = phase;
		grainPos = grainPos - (SampleRate.ir*length*(pitchRatio - 1).max(0));
		grainPos = grainPos.wrap(0, BufFrames.kr(sampleBuf));

		out = (2/overlap)*GrainBuf.ar(1, grainTrigs, length, sampleBuf, pitchRatio, (grainPos/BufFrames.kr(sampleBuf)).wrap(0, 1));
		^out;
	}
}

