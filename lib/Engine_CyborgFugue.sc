CyborgFugeVoice {
  var group, voiceInBus, infoBus, beatDurBus, degreeBus, <outBus, soundBuf, infoBuf, degreeBuf, scaleBuf, recorder, reader, routine, phasorBus; 
  var root, <>period, <>rate, <delay, <amp, <pan, <degreeMult, <degreeAdd;
  
  *new { |group, voiceInBus, beatDurBus, infoBus, degreeBus, scaleBuf|
    var sampleRate = Server.default.sampleRate;
    var controlRate = sampleRate/Server.default.options.blockSize;
    var soundBuf = Buffer.alloc(Server.default, sampleRate*40, 1);
    var infoBuf = Buffer.alloc(Server.default, (controlRate*40)+1, 2);
    var degreeBuf = Buffer.alloc(Server.default, (controlRate*40)+1, 1);
    var outBus = Bus.audio(numChannels: 2);
    var phasorBus = Bus.audio(numChannels: 1);
      
    var ret = super.newCopyArgs(
      group, voiceInBus, infoBus, beatDurBus, degreeBus, outBus, soundBuf, infoBuf, degreeBuf, scaleBuf, nil, nil, nil, phasorBus, 
      60, 1, 1, 1, 1, 0, 1, 0);
    ret.init;
    ^ret;
  }
  
  root_ { |r|
    root = r;
    if (reader != nil, {
      reader.set(\scaleRoot, r);
    });
  }
  
  delay_ { |d|
    delay = d;
    this.replaceReader;
  }
  
  amp_ { |a|
    amp = a;
    if (reader != nil, {
      reader.set(\amp, a);
    });
  }
  
  pan_ { |p|
    pan = p;
    if (reader != nil, {
      reader.set(\pan, p);
    });
  }
  
  degreeMult_ { |d|
    degreeMult = d;
    if (reader != nil, {
      reader.set(\degreeMult, d);
    });
  }
  
  degreeAdd_ { |d|
    degreeAdd = d;
    if (reader != nil, {
      reader.set(\degreeAdd, d);
    });
  }  
  
  replaceReader {
    var replacement;
    var old = reader;
    Server.default.makeBundle(0.1/TempoClock.tempo, {
      // first end the old reader
      if (reader != nil, {
        old.set(\gate, 0);
      });
      // then start a new one
      replacement = Synth(\reader, [
        out: outBus,
        beatDurBus: beatDurBus,
        phasorBus: phasorBus,
        soundBuffer: soundBuf,
        infoBuffer: infoBuf,
        degreeBuffer: degreeBuf,
        degreeMult: degreeMult,
        degreeAdd: degreeAdd,
        scaleBuffer: scaleBuf,
        scaleRoot: root,
        rate: rate,
        formantRatio: 1,
        delay: delay,
        amp: amp,
        pan: pan,
      ], addAction: \addAfter, target: recorder);
      // ("made " ++ replacement.nodeID).postln;
    });
    reader = replacement;
  }

  init {
    routine = Routine.new({
      var lastDelayTime = delay;
      Server.default.sync;
      recorder = Synth.new(\recorder, [
        infoBus: infoBus, 
        voiceInBus: voiceInBus, 
        degreeBus: degreeBus,
        soundBuffer: soundBuf,
        infoBuffer: infoBuf,
        degreeBuffer: degreeBuf,
        freeze: 0, 
        phasorBus: phasorBus], addAction: \addToHead, target: group);
      loop {
        var nextActivation;
        Server.default.sync;
        nextActivation = TempoClock.timeToNextBeat(period) - 0.1;
        if (nextActivation <= 0, { 
          "ooops".postln;
          nextActivation = 0.05
        });
        nextActivation.wait;
        // We want to replace the reader if:
        // * It's nil
        // * Rate is not 1
        // * We have changed the delay time
        if ( (reader == nil) || (rate != 1) || (delay != lastDelayTime), {
          this.replaceReader;
        });
        lastDelayTime = delay;
        (0.101).wait;
      };
    }).play;
  }
  
  free {
    routine.stop;
    recorder.free;
    reader.free;
    soundBuf.free;
    degreeBuf.free;
    infoBuf.free;
    phasorBus.free;
    outBus.free;
  }
}



Engine_CyborgFugue : CroneEngine {
	classvar luaOscPort = 10111;

  var pitchFinderSynth, infoBus, voiceInBus, backgroundBus, degreeBus, voices, pitchHandler, endOfChainSynth, scaleBuffer, beatDurBus;
  var inL, inR, backL, backR, backPan;
  
	*new { arg context, doneCallback;
    	  
		^super.new(context, doneCallback);
	}
	  

  alloc {
    var group;
  	var luaOscAddr = NetAddr("localhost", luaOscPort);
  	var scale = FloatArray[0, 2, 3.2, 5, 7, 9, 10];
  	scaleBuffer = Buffer.alloc(Server.default, scale.size, 1, {|b| b.setnMsg(0, scale) });
  	beatDurBus = Bus.control(numChannels: 1);
  	beatDurBus.set(1/TempoClock.tempo);

	  pitchHandler == OSCdef.new(\pitchHandler, { |msg, time|
			var pitch = msg[3].asFloat;

			luaOscAddr.sendMsg("/measuredPitch", pitch);
		}, '/tr');
		
		this.addCommand("tempo_sync", "ff", { arg msg;
			var beats = msg[1].asFloat;
			var tempo = msg[2].asFloat;
			var beatDifference = beats - TempoClock.default.beats;
			var nudge = beatDifference % 4;
			if (nudge > 2, {nudge = nudge - 4});
			if ( (tempo != TempoClock.default.tempo) || (nudge.abs > 1), {
				TempoClock.default.beats = TempoClock.default.beats + nudge;
				TempoClock.default.tempo = tempo;
			}, {
				TempoClock.default.beats = TempoClock.default.beats + (0.05 * nudge);
			});
			// Set M to be the duration of a beat.
			beatDurBus.set(1/tempo);
		});
		
		this.addCommand("setMix", "fffff", { |msg|
		  var inL = msg[1].asFloat;
		  var inR = msg[2].asFloat;
		  var backL = msg[3].asFloat;
		  var backR = msg[4].asFloat;
		  var backPan = msg[5].asFloat;
		
		  if (pitchFinderSynth != nil, {
		    pitchFinderSynth.set(
		      \inL, inL,
		      \inR, inR,
		      \backL, backL,
		      \backR, backR,
		      \backgroundPan, backPan);
		  });
		});
		
		this.addCommand("setScale", "iiiiiiiiiiiiii", { |msg|
		  var len = msg[1].asInteger;
		  var root = msg[2].asInteger;
		  var array = FloatArray.fill(len-1, { |i|
		    msg[i + 3].asFloat;
		  });
		  array.postln;
		  scaleBuffer.numFrames = array.size;
		  scaleBuffer.alloc({|b| b.setnMsg(0, array)});
		  voices.do { |v|
		    v.root = root;
		  };
		});
		
		this.addCommand("setDelay", "if", { |msg|
		  var voice = msg[1].asInteger;
		  var delay = msg[2].asFloat;
		  voices[voice].delay = delay;
		});
		
		this.addCommand("setAmp", "if", { |msg|
		  var voice = msg[1].asInteger;
		  var amp = msg[2].asFloat;
		  voices[voice].amp = amp;
		});
		
		this.addCommand("setPan", "if", { |msg|
		  var voice = msg[1].asInteger;
		  var pan = msg[2].asFloat;
		  voices[voice].pan = pan;
		});
		
		this.addCommand("setDegreeMult", "if", { |msg|
		  var voice = msg[1].asInteger;
		  var degreeMult = msg[2].asFloat;
		  voices[voice].degreeMult = degreeMult;
		});
		
		this.addCommand("setDegreeAdd", "if", { |msg|
		  var voice = msg[1].asInteger;
		  var degreeAdd = msg[2].asFloat;
		  voices[voice].degreeAdd = degreeAdd;
		});

		this.addCommand("setPeriod", "if", { |msg|
		  var voice = msg[1].asInteger;
		  var period = msg[2].asFloat;
		  voices[voice].period = period;
		});
		
		this.addCommand("setRate", "if", { |msg|
		  var voice = msg[1].asInteger;
		  var rate = msg[2].asFloat;
		  voices[voice].rate = rate;
		});				
		
		this.addCommand("setInputRange", "ff", { |msg|
		  var low = msg[1].asFloat;
		  var high = msg[2].asFloat;
		  Routine({
  		  if (pitchFinderSynth != nil, {
  		    "freeing pitch".postln;
    		  pitchFinderSynth.free;
  	  	  pitchFinderSynth = nil;
    		});
    		Server.sync;
    		"starting pitch".postln;
	  	  pitchFinderSynth = Synth(\follower, [
	  	    infoBus: infoBus,  
	  	    voiceInBus: voiceInBus, 
	  	    backgroundBus: backgroundBus, 
	  	    inL: inL, 
	  	    inR: inR,
	  	    backL: backL,
	  	    backR: backR,
	  	    backgroundPan: backPan,
	  	    minFreq:low, 
	  	    maxFreq:high]);
	  	}).play;
		});
		
		this.addCommand("scaleDegree", "i", { |msg|
		  var degree = msg[1].asInteger;
		  degreeBus.set(degree);
		});
		
  
    //Routine.new({
      infoBus = Bus.control(numChannels: 2);
      degreeBus = Bus.control(numChannels: 1);
      backgroundBus = Bus.audio(numChannels:2);
      voiceInBus = Bus.audio(numChannels: 1);
      
      SynthDef(\endOfChain, { |a, b, c, d|
        var mix = Mix.ar([a, b, c, d].collect({ |v|
          In.ar(v, 2)
        }));
        voices.size.postln;
        mix.post;
        Out.ar(0, mix.softclip);
      }).add;
      
      SynthDef(\follower, { |infoBus, voiceInBus, backgroundBus, inL, inR, backL, backR, backgroundPan, minFreq=82, maxFreq=1046|
        var in = SoundIn.ar([0, 1]);
        var snd = Mix.ar([inL, inR]*in);
        var background = Mix.ar([backL, backR]*in);
        var reference = LocalIn.kr(1);
        var info = Pitch.kr(snd, minFreq: minFreq, maxFreq: maxFreq);
        var midi = info[0].cpsmidi;
        var trigger = info[1]*((midi - reference).abs > 0.2);
        LocalOut.kr([Latch.kr(midi, trigger)]);
        SendTrig.kr(trigger, 0, info[0]);
        Out.kr(infoBus, info);
        Out.ar(voiceInBus, snd);
        Out.ar(backgroundBus, Pan2.ar(background, backgroundPan));
      }).add;
      
      SynthDef(\recorder, { |infoBus, voiceInBus, degreeBus, soundBuffer, infoBuffer, degreeBuffer, freeze=0, delay=4, phasorBus|
        var phasor = Phasor.ar(0, 1, 0, BufFrames.kr(soundBuffer));
        var controlPhasor = phasor*(ControlRate.ir/SampleRate.ir);
        var doFeedback = freeze.lag(0.1);
        //Poll.kr(Impulse.kr(1), In.ar(voiceInBus), "sound production");
        
        var feedbackSound = BufRd.ar(1, soundBuffer, phasor - (SampleRate.ir*delay));
        var feedbackDegree = BufRd.kr(1, degreeBuffer, controlPhasor - (ControlRate.ir*delay));
        var feedbackInfo = BufRd.kr(2, infoBuffer, controlPhasor - (ControlRate.ir*delay));
        
        var recordSound = doFeedback.if(feedbackSound, In.ar(voiceInBus, 1));
        var recordDegree = freeze.if(feedbackDegree, In.kr(degreeBus, 1));
        var recordInfo = freeze.if(feedbackInfo, In.kr(infoBus, 2));
        
        //Poll.kr(Impulse.kr(1), controlPhasor, "controlPhaseProduced");

        BufWr.ar([recordSound], soundBuffer, phasor);
        BufWr.kr([recordDegree], degreeBuffer, controlPhasor);
        BufWr.kr(recordInfo, infoBuffer, controlPhasor);
        
        Out.ar(phasorBus, [phasor]);
        //Poll.kr(Impulse.kr(1), phasor, "phaseProduced");
        
      }).add;
      
      SynthDef(\repeater, { |out, inBus, beatDurBus, repeatTime, feedback, rotate|
        var in = inBus.ar(inBus, numChannels: 2);
        Out.ar(out, PingPong.ar(LocalBuf.new(4*SampleRate.ir, 2), in, In.kr(beatDurBus)*repeatTime, feedback, rotate));
      }).add;
      
      SynthDef(\reader, { |out, phasorBus, beatDurBus, soundBuffer, infoBuffer, degreeBuffer, degreeMult, degreeAdd, scaleBuffer, scaleRoot,
                           delay, gate=1, smoothing=0.2, rate=1, formantRatio=1, vibratoAmount=0.1, vibratoSpeed=3, amp=1, pan=0|
        var controlPhasor, hz, note, degree, sound;
        var beatDur = In.kr(beatDurBus);
        var phasor = In.ar(phasorBus, numChannels: 1);
        var delayPhasorRate = rate - 1;
        // Reset the delay phasor when we unfreeze.
        var envelope =  EnvGen.kr(Env.asr(attackTime: smoothing, releaseTime: smoothing, curve: 0), gate, doneAction: Done.freeSelf);
        var delayPhasor = Phasor.ar(0, delayPhasorRate, 0, (delayPhasorRate > 0).if(1, -1)*40*SampleRate.ir);
        phasor = phasor + delayPhasor - (delay*beatDur*SampleRate.ir);
        //Poll.kr(Impulse.kr(1), phasor, "phase");

        phasor = phasor.wrap(0, BufFrames.kr(soundBuffer));
        //Poll.kr(Impulse.kr(1), phasor, "phase2");
        
        controlPhasor = phasor*(ControlRate.ir/SampleRate.ir);
        degree = BufRd.kr(1, degreeBuffer, controlPhasor);
        //Poll.kr(Impulse.kr(1), degree, "degree");
        // Poll.kr(Impulse.kr(1), controlPhasor, "controlPhase");
        degree = (degreeMult * degree) + degreeAdd;
        //Poll.kr(Impulse.kr(1), BufFrames.kr(scaleBuffer), "scale len");
        note = scaleRoot + DegreeToKey.kr(scaleBuffer, degree, 12) + (vibratoAmount*SinOsc.kr(vibratoSpeed));
        //Poll.kr(Impulse.kr(1), note, "note");
        hz = note.midicps;
        //Poll.kr(Impulse.kr(1), hz, "hz");
        sound = PSOLABufRead.ar(soundBuffer, infoBuffer, phasor, rate, hz, formantRatio, 2, 0.01);
        //Poll.kr(Impulse.kr(1), sound, "sound");
        sound = amp*Pan2.ar(sound, pan);
        Out.ar(out, envelope*sound);
      }).add;
      
      //Server.default.sync;
      // This runs the whole time.
      pitchFinderSynth = Synth(\follower, [infoBus: infoBus, voiceInBus: voiceInBus, backgroundBus: backgroundBus, inL: 0.5, inR: 0.5, backL: 0, backR: 0, backPan: 0]);
      group = Group.after(pitchFinderSynth);
      voices = 4.collect({CyborgFugeVoice.new(group, voiceInBus, beatDurBus, infoBus, degreeBus, scaleBuffer)});
      endOfChainSynth = Synth.after(group, \endOfChain, [a: voices[0].outBus, b: voices[1].outBus, c: voices[2].outBus, d: voices[3].outBus]);

    //}).play;
  }
  
  free {
    voices.do { |v|
      v.free
    };    
    pitchFinderSynth.free;
    infoBus.free;
    pitchHandler.free;
    endOfChainSynth.free;
    backgroundBus.free;
    degreeBus.free;
    voiceInBus.free;
    beatDurBus.free;
  }
}