-- Borgue
-- @sixolet
--
-- Cyborg Fugue
--
-- E1: Select voice
-- E2: Transpose
-- E3: Delay
-- K2: Invert
-- K3: Freeze
-- K1+E2: Amp
-- K1+E3: Extent
-- K1+K2: Mute
-- K1+K3: Reverse

music = require 'musicutil'
include('borgue/lib/passencorn')


engine.name = "CyborgFugue"
SCALE_NAMES = {}
for i, v in pairs(music.SCALES) do
  SCALE_NAMES[i] = v["name"]
end

scale = nil
scaleSet = {}
activePitchClasses = {}
sungNote = nil
rootDegree = nil;

function partial2(f, arg1)
  return function(arg2)
    f(arg1, arg2)
  end
end

function set_scale()
  scale = music.generate_scale(params:get("root") % 12, scale_name(), 10)
  rootDegree = find(scale, params:get("root"))
  local octave = music.generate_scale(0, scale_name(), 1)
  local octaveLen = #octave
  while #octave < 12 do
    table.insert(octave, -1)
  end
  engine.setScale(octaveLen, params:get("root"), 
    octave[1], octave[2], octave[3], octave[4], octave[5], octave[6], octave[7], octave[8], octave[9], octave[10], octave[11], octave[12]);
  scaleSet = {}
  for i, note in ipairs(scale) do
    scaleSet[note % 12] = true
  end
end

function scale_name()
  return SCALE_NAMES[params:get("scale")]
end



function redraw()
  screen.clear()
  screen.aa(1)
  local x, y
  for i=0,11,1 do
    if scaleSet[i] ~= nil then
      screen.level(15)
    else
      screen.level(0)
    end
    x = 64 - 35*math.sin(2 * math.pi * (i/12))
    y = 32 + 25*math.cos(2 * math.pi * (i/12))
    screen.circle(x, y, 2)
    screen.fill()
    if sungNote ~= nil and sungNote % 12 == i then
      screen.move(64, 32)
      screen.line(x, y)
      screen.stroke()
    end
  end
  screen.level(8)
  local count = 0
  for i=0,11,1 do
    if activePitchClasses[i] == true then
      x = 64 - 35*math.sin(2 * math.pi * (i/12))
      y = 32 + 25*math.cos(2 * math.pi * (i/12))
      if count == 0 then
        screen.move(x, y)
      else
        screen.line(x, y)
      end
      count = count + 1
    end
  end
  if count > 1 then
    screen.close()
    screen.stroke()
  elseif count == 1 then
    screen.circle(x, y, 6)
    screen.stroke()
  end
  if unquantizedSungNote ~= nil then
    local i = unquantizedSungNote % 12
    local x = 64 - 10*math.sin(2 * math.pi * (i/12))
    local y = 32 + 7*math.cos(2 * math.pi * (i/12))
    screen.level(8)
    screen.move(64, 32)
    screen.line(x, y)
    screen.stroke()
  end
  screen.update()
end

function change_range()
  if params:get("high") < 2*params:get("low") then
    params:set("high", 2*params:get("low"))
  end
  engine.setInputRange(params:get("low"), params:get("high"))
end

function change_input_mix()
  if params:get("style") == 1 then -- mix to mono
    engine.setMix(0.5, 0.5, 0, 0, 0);
    params:hide("background amp")
    params:hide("background pan")
  elseif params:get("style") == 2 then
    params:show("background amp")
    params:show("background pan")    
    engine.setMix(1, 0, 0, params:get("background amp"), params:get("background pan"))
  end
  _menu.rebuild_params()
end

AMPSPEC = controlspec.AMP:copy()
AMPSPEC.default = 0.5

function init()
  osc.event = osc_in
  screen_redraw_clock = clock.run(
    function()
      while true do
        clock.sleep(1/15) 
        if screen_dirty == true then
          redraw()
          screen_dirty = false
        end
      end
    end
  )
  params:add_separator("quantization")
  params:add_number(
    "root", -- id
    "root", -- name
    0, -- min
    127, -- max
    60, -- default
    function(param) return music.note_num_to_name(param:get(), true) end, -- formatter
    true -- wrap
    )
  params:set_action("root", set_scale)
  params:add_option("scale", "scale", SCALE_NAMES, 1)
  params:set_action("scale", set_scale)
  hysteresis_spec = controlspec.UNIPOLAR:copy()
  hysteresis_spec.default = 0.5
  params:add_control("hysteresis", "hysteresis", hysteresis_spec)
  local lowspec = controlspec.FREQ:copy()
  lowspec.default = 82
  local highspec = controlspec.FREQ:copy()
  highspec.default = 1046
  params:add_control("low", "in range low", lowspec)
  params:add_control("high", "in range high", highspec)
  
  -- Set up voice 0
  engine.setDegreeMult(0, 1)
  engine.setDegreeAdd(0, 0)
  clock.run(function ()
    clock.sleep(0.1)
    engine.setDelay(0, 0)
  end)
  
  params:add_separator("lead")
  params:add_control("lead amp", "lead amp", AMPSPEC)
  params:set_action("lead amp", function (amp) engine.setAmp(0, amp) end)
  params:add_control("lead pan", "lead pan", controlspec.PAN)
  params:set_action("lead pan", function (pan) engine.setPan(0, pan) end)

  for i=1,3,1 do
    add_voice_params(i)
  end
  
  params:add_separator("source")
  params:add_option("style", "style", {"mix LR mono", "L voice R background"}, 1)
  params:set_action("style", change_input_mix)
  params:add_control("background amp", "background amp", amp_spec)
  params:set_action("background amp", change_input_mix)
  params:add_control("background pan", "background pan", controlspec.BIPOLAR)
  params:set_action("background pan", change_input_mix)

  params:read()
  params:bang()
end

function add_voice_params(i)
  params:add_separator("voice ".. i)
  params:add_control("delay "..i, "delay "..i, controlspec.new(0.5, 16, 'exp', 0, i, "beats"))
  params:set_action("delay "..i, function(delay)
    engine.setDelay(i, delay)
  end)
  params:add_control("amp " ..i, "amp "..i, AMPSPEC)
  params:set_action("amp "..i, function(amp)
    engine.setAmp(i, amp)
  end)
  params:add_control("pan "..i, "pan "..i, controlspec.PAN)
  params:set_action("pan "..i, function(pan)
    engine.setPan(i, pan)
  end)
  params:add_number("add "..i, "add "..i, -28, 28, i*2)
  params:set_action("add "..i, function(add)
      engine.setDegreeAdd(i, add)
  end) 
  params:add_binary("invert "..i, "invert "..i, "toggle", 0)
  params:set_action("invert "..i, function(invert)
    if invert == 0 then
      engine.setDegreeMult(i, 1)
    else
      engine.setDegreeMult(i, -1)
    end
  end)  
end


function osc_in(path, args, from)

  if path == "/measuredPitch" then
    local pitch = args[1]
    -- print(pitch)
    unquantizedSungNote = freq_to_note_num_float(pitch)
    screen_dirty = true
    if scale == nil then
      return
    end
    -- Introduce a little bit of hysteresis if we're near
    if scale ~= nil then
      local newNote = quantize(scale, pitch, sungNote, params:get("hysteresis"))
      if sungNote ~= newNote then
        -- print("pitch", pitch, "unquant", unquantizedSungNote, "quant", newNote)
        sungNote = newNote
        local degree = scaleDegree(scale, sungNote)
        engine.scaleDegree(degree - rootDegree)
        print("degree", degree, "pitch", pitch, "sungNote", sungNote, "degree diff", degree - rootDegree)
      end
    end
  end
end
