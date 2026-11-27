(function (global) {
  'use strict';

  var BG_HEX = 0x161719;
  var DEFAULT_THETA = 0.76;   // Classic front-left 3/4 hero angle
  var DEFAULT_PHI = 1.38;     // Sleek ~79° eye-level automotive showroom angle
  var CAR_CENTER_Y = 0.58;    // True optical vertical center of the 3D vehicle
  var HERO_RADIUS = 8.45;     // Initial fallback radius before first resize
  var WHEELBASE_M = 2.77;     // Arrizo 8 physical wheelbase (meters)
  var DEFAULT_WHEEL_R = 0.342;

  var renderer, scene, camera, envMap, gltfLoader;
  var currentContainer = null;
  var currentChassis = null;
  var currentModelKey = null;
  var activeLoadedUrl = null;
  var currentEffects = null;
  var currentRig = null;
  var currentModelSize = null;
  var parkingBay = null;
  var renderActive = true;
  var rafId = null;
  var lastTime = 0;
  var elapsed = 0;
  var hasCustomDemoAngle = false;
  var hasNotifiedFirstDrag = false;
  var collapsedVisibleRatio = 0.64;
  var expandedVisibleRatio = 1.0;
  var smoothDeckProgress = 0;
  var lastViewOffsetY = -1;
  var lastViewOffsetW = 0;
  var lastViewOffsetH = 0;

  var ENTRANCE_STYLES = ['right_drive', 'left_drive', 'reverse_park', 'bay_exit'];
  var preferredEntranceStyle = 'auto';
  var lastPlayedEntranceStyle = '';
  var reverseParkSide = 1; // Alternates between +1 (Left Side Profile) and -1 (Right Side Profile)
  var bayExitSide = -1; // Alternates between -1 (Right Side-Front) and +1 (Left Side-Front)
  var activeRestTheta = DEFAULT_THETA;
  var activeRestPhi = DEFAULT_PHI;

  function wrapAngleDelta(fromRad, toRad) {
    var twoPi = Math.PI * 2;
    var diff = ((toRad - fromRad) % twoPi + twoPi) % twoPi;
    if (diff > Math.PI) diff -= twoPi;
    return diff;
  }

  // Dynamic entrance camera framing offset (smoothly returns to 0 on settle and resets in finishEntranceImmediately)
  var entranceCam = {
    offsetX: 0,
    offsetZ: 0,
    radiusBoost: 0
  };

  function normalizeEntranceStyle(raw) {
    if (typeof raw !== 'string') return 'auto';
    var trimmed = raw.trim();
    if (trimmed === 'auto') return 'auto';
    for (var i = 0; i < ENTRANCE_STYLES.length; i++) {
      if (ENTRANCE_STYLES[i] === trimmed) return trimmed;
    }
    return 'auto';
  }

  function resolveEntranceStyle(styleOverride) {
    var hasOverride = typeof styleOverride === 'string' && styleOverride.trim().length > 0;
    var requested = hasOverride ? normalizeEntranceStyle(styleOverride) : preferredEntranceStyle;
    if (requested !== 'auto') {
      lastPlayedEntranceStyle = requested;
      return requested;
    }
    var pool = [];
    for (var i = 0; i < ENTRANCE_STYLES.length; i++) {
      if (ENTRANCE_STYLES[i] !== lastPlayedEntranceStyle) {
        pool.push(ENTRANCE_STYLES[i]);
      }
    }
    if (pool.length === 0) pool = ENTRANCE_STYLES;
    var chosen = pool[Math.floor(Math.random() * pool.length)];
    lastPlayedEntranceStyle = chosen;
    return chosen;
  }

  /**
   * Minimalist dark-studio architectural parking bay (Y = 0.004) with subtle cool-white
   * border hairlines, 4 corner L-brackets, and rear wheel stop bar.
   */
  function createParkingBay(modelSize) {
    var sx = (modelSize && modelSize.x > 0.5) ? modelSize.x : 1.92;
    var sz = (modelSize && modelSize.z > 0.5) ? modelSize.z : 4.78;
    var halfW = clamp(sx * 0.66, 1.24, 1.48);
    var halfL = clamp(sz * 0.56, 2.58, 2.86);

    var group = new THREE.Group();
    group.name = 'studio_parking_bay';
    group.position.set(0, 0.004, 0);
    group.visible = false;

    var lineMat = new THREE.MeshBasicMaterial({
      color: 0xcbd5e1,
      transparent: true,
      opacity: 0.0,
      depthWrite: false
    });
    var cornerMat = new THREE.MeshBasicMaterial({
      color: 0xe2e8f0,
      transparent: true,
      opacity: 0.0,
      depthWrite: false
    });
    var stopMat = new THREE.MeshBasicMaterial({
      color: 0x94a3b8,
      transparent: true,
      opacity: 0.0,
      depthWrite: false
    });

    function addStrip(w, l, x, z, mat) {
      var mesh = new THREE.Mesh(new THREE.PlaneGeometry(w, l), mat);
      mesh.rotation.x = -Math.PI / 2;
      mesh.position.set(x, 0, z);
      group.add(mesh);
    }

    var tLine = 0.036;
    var tCorner = 0.048;
    var cLen = 0.44;

    // Left, right, and rear architectural border hairlines
    addStrip(tLine, halfL * 2, -halfW, 0, lineMat);
    addStrip(tLine, halfL * 2, halfW, 0, lineMat);
    addStrip(halfW * 2 + tLine, tLine, 0, -halfL, lineMat);

    // 4 subtle L-bracket corner accents
    for (var sxSign = -1; sxSign <= 1; sxSign += 2) {
      for (var szSign = -1; szSign <= 1; szSign += 2) {
        addStrip(cLen, tCorner, sxSign * (halfW - cLen * 0.5), szSign * halfL, cornerMat);
        addStrip(tCorner, cLen, sxSign * halfW, szSign * (halfL - cLen * 0.5), cornerMat);
      }
    }

    // Minimalist rear wheel-stop bar
    addStrip(halfW * 1.18, 0.095, 0, -halfL + 0.52, stopMat);

    return {
      group: group,
      setPose: function (x, z, yaw) {
        group.position.set(x, 0.004, z);
        group.rotation.set(0, yaw, 0);
      },
      setAlpha: function (a) {
        var c = clamp(a, 0, 1);
        group.visible = c > 0.003;
        lineMat.opacity = c * 0.26;
        cornerMat.opacity = c * 0.48;
        stopMat.opacity = c * 0.30;
      }
    };
  }

  // Damped spring-mass chassis suspension state (body moves independently over grounded wheels)
  var suspension = {
    pitch: 0, pitchVel: 0,
    roll: 0, rollVel: 0,
    heave: 0, heaveVel: 0
  };

  function impulseSuspension(dPitch, dRoll, dHeave) {
    suspension.pitchVel += dPitch;
    suspension.rollVel += dRoll;
    suspension.heaveVel += dHeave;
  }

  function stepSuspension(dt, targetPitch, targetRoll, targetHeave) {
    var kPitch = 36.0, cPitch = 8.4;
    var kRoll = 42.0, cRoll = 9.2;
    var kHeave = 48.0, cHeave = 10.0;

    var accP = kPitch * (targetPitch - suspension.pitch) - cPitch * suspension.pitchVel;
    var accR = kRoll * (targetRoll - suspension.roll) - cRoll * suspension.rollVel;
    var accH = kHeave * (targetHeave - suspension.heave) - cHeave * suspension.heaveVel;

    suspension.pitchVel += accP * dt;
    suspension.rollVel += accR * dt;
    suspension.heaveVel += accH * dt;

    suspension.pitch += suspension.pitchVel * dt;
    suspension.roll += suspension.rollVel * dt;
    suspension.heave += suspension.heaveVel * dt;
  }

  // Multi-style physically grounded entrance choreographer:
  // Supports 'right_drive', 'left_drive', 'reverse_park', and 'bay_exit' with multi-segment
  // Bezier paths, brake-hold pauses, forward/reverse wheel rolling, Ackermann front steering,
  // DRL/brake/reverse lighting, damped spring suspension, and minimalist ground parking bay.
  var entrance = {
    active: false,
    style: 'right_drive',
    elapsed: 0,
    totalDuration: 2.0,
    segments: [],
    bayPose: null, // { x, z, yaw, fadeInEnd, fadeOutStart, fadeOutEnd } or null
    camProfile: null, // { startTheta, startPhi, restTheta, restPhi, orbitStart, orbitEnd, offsetX, offsetZ, radiusBoost, fadeInEnd, returnStart, returnEnd } or null
    prevX: 0,
    prevZ: 0,
    prevSpeed: 0,
    prevSegIdx: -1,
    wheelSpin: 0,
    steerAngle: 0,
    headGlow: 0,
    brakeGlow: 0,
    reverseGlow: 0,
    bayAlpha: 0,
    arrivalBlinkFired: false
  };

  function evalCubicBezier(p0, p1, p2, p3, u) {
    var inv = 1.0 - u;
    var inv2 = inv * inv;
    var u2 = u * u;
    return {
      x: inv2 * inv * p0.x + 3 * inv2 * u * p1.x + 3 * inv * u2 * p2.x + u2 * u * p3.x,
      z: inv2 * inv * p0.z + 3 * inv2 * u * p1.z + 3 * inv * u2 * p2.z + u2 * u * p3.z
    };
  }

  function evalCubicBezierDeriv(p0, p1, p2, p3, u) {
    var inv = 1.0 - u;
    return {
      x: 3 * inv * inv * (p1.x - p0.x) + 6 * inv * u * (p2.x - p1.x) + 3 * u * u * (p3.x - p2.x),
      z: 3 * inv * inv * (p1.z - p0.z) + 6 * inv * u * (p2.z - p1.z) + 3 * u * u * (p3.z - p2.z)
    };
  }

  function evalCubicBezierSecondDeriv(p0, p1, p2, p3, u) {
    var inv = 1.0 - u;
    return {
      x: 6 * inv * (p2.x - 2 * p1.x + p0.x) + 6 * u * (p3.x - 2 * p2.x + p1.x),
      z: 6 * inv * (p2.z - 2 * p1.z + p0.z) + 6 * u * (p3.z - 2 * p2.z + p1.z)
    };
  }

  function evalEaseProfile(profile, t) {
    var x = clamp(t, 0, 1);
    if (profile === 'decel') {
      // Brisk entry -> progressive automotive braking stop
      return 1.0 - Math.pow(1.0 - x, 2.75);
    }
    if (profile === 'start_stop') {
      // Smooth accelerate from standstill -> cruise -> progressive brake stop
      var s = x * x * (3.0 - 2.0 * x);
      return 1.0 - Math.pow(1.0 - s, 1.35);
    }
    return x * x * (3.0 - 2.0 * x);
  }

  function buildEntranceChoreography(styleKey, revSideOverride) {
    // Camera-aligned lateral screen-right unit vector for DEFAULT_THETA (+0.76)
    var rx = Math.cos(DEFAULT_THETA);
    var rz = -Math.sin(DEFAULT_THETA);

    if (styleKey === 'left_drive') {
      // Crisp close-in lateral sweep from off-screen left — exact mirror of right_drive
      // around the mirrored Front-Right 3/4 showcase camera (-DEFAULT_THETA, 1.36).
      var leftTheta = -DEFAULT_THETA;
      var lRx = Math.cos(leftTheta);
      var lRz = -Math.sin(leftTheta);
      return {
        bayPose: null,
        camProfile: {
          startTheta: leftTheta,
          startPhi: 1.36,
          restTheta: leftTheta,
          restPhi: 1.36,
          orbitStart: 0.0,
          orbitEnd: 0.0,
          offsetX: 0,
          offsetZ: 0,
          radiusBoost: 0,
          fadeInEnd: 0,
          returnStart: 0,
          returnEnd: 0.01
        },
        segments: [
          {
            type: 'drive',
            dir: 1,
            duration: 1.72,
            ease: 'decel',
            p0: { x: -lRx * 7.8, z: -lRz * 7.8 - 1.2 },
            p1: { x: -lRx * 3.8, z: -lRz * 3.8 - 1.8 },
            p2: { x: 0.0, z: -2.45 },
            p3: { x: 0.0, z: 0.0 },
            straightFadeStart: 0.76,
            headStart: 0.65,
            headEnd: 0.36,
            brakeStartT: 0.18,
            brakePeakT: 0.45,
            stopImpulse: [-0.085, 0.0, 0.012],
            stopFired: false
          },
          {
            type: 'hold',
            duration: 0.28,
            x: 0.0,
            z: 0.0,
            yaw: 0.0,
            steerTarget: 0.0,
            brakeStart: 0.82,
            brakeEnd: 0.0,
            headStart: 0.36,
            headEnd: 0.0,
            reverseStart: 0.0,
            reverseEnd: 0.0
          }
        ]
      };
    }

    if (styleKey === 'reverse_park') {
      // Side-staged reverse park: drives forward along the parallel aisle beside the bay,
      // brakes & shifts into R (white reverse lights + front counter-steer), then reverses ~5.85m
      // along a strictly convex C1 J-curve into the bay at (0, 0, 0) facing +Z while camera
      // pans smoothly into a crisp Full Left or Right Side Profile (±1.52 rad, ~±87°).
      var side = (revSideOverride === -1 || revSideOverride === 1) ? revSideOverride : reverseParkSide;
      var stopX = -2.85 * side;
      var stopZ = 4.95;
      var stopYaw = -0.80 * side;
      var sinY = Math.sin(stopYaw);
      var cosY = Math.cos(stopYaw);
      var revAlpha = Math.abs(stopX) / Math.abs(sinY); // intersection with x = 0 at Q = (0, +2.182)
      var qZ = stopZ - cosY * revAlpha;
      var fwdP2 = { x: stopX - sinY * 1.55, z: stopZ - cosY * 1.55 };
      var revP1 = { x: stopX - sinY * (0.62 * revAlpha), z: stopZ - cosY * (0.62 * revAlpha) };
      return {
        bayPose: { x: 0.0, z: 0.0, yaw: 0.0, fadeInEnd: 0.40, fadeOutStart: 3.58, fadeOutEnd: 4.06 },
        camProfile: {
          startTheta: 1.05 * side,
          startPhi: 1.34,
          restTheta: 1.52 * side,
          restPhi: 1.38,
          orbitStart: 1.45,
          orbitEnd: 3.56,
          offsetX: -0.75 * side,
          offsetZ: 1.85,
          radiusBoost: 2.9,
          fadeInEnd: 0.0,
          returnStart: 1.50,
          returnEnd: 3.52
        },
        segments: [
          {
            type: 'drive',
            dir: 1,
            duration: 1.15,
            ease: 'decel',
            p0: { x: -3.4 * side, z: -6.8 },
            p1: { x: -2.6 * side, z: 0.2 },
            p2: fwdP2,
            p3: { x: stopX, z: stopZ },
            straightFadeStart: 0.96,
            headStart: 0.65,
            headEnd: 0.48,
            brakeStartT: 0.50,
            brakePeakT: 0.86,
            stopImpulse: [-0.055, 0.0, 0.008],
            stopFired: false
          },
          {
            type: 'hold',
            duration: 0.36,
            x: stopX,
            z: stopZ,
            yaw: stopYaw,
            steerTarget: -0.25 * side,
            brakeStart: 0.88,
            brakeEnd: 0.25,
            headStart: 0.48,
            headEnd: 0.44,
            reverseStart: 0.0,
            reverseEnd: 1.0,
            reverseSwitchT: 0.40
          },
          {
            type: 'drive',
            dir: -1,
            duration: 2.05,
            ease: 'start_stop',
            p0: { x: stopX, z: stopZ },
            p1: revP1,
            p2: { x: 0.0, z: 0.68 * qZ },
            p3: { x: 0.0, z: 0.0 },
            straightFadeStart: 0.80,
            headStart: 0.44,
            headEnd: 0.34,
            brakeStartT: 0.72,
            brakePeakT: 0.94,
            reverseActive: true,
            stopImpulse: [0.068, 0.0, 0.010],
            stopFired: false
          },
          {
            type: 'hold',
            duration: 0.50,
            x: 0.0,
            z: 0.0,
            yaw: 0.0,
            steerTarget: 0.0,
            brakeStart: 0.80,
            brakeEnd: 0.0,
            headStart: 0.34,
            headEnd: 0.0,
            reverseStart: 0.65,
            reverseEnd: 0.0,
            reverseReleaseT: 0.35
          }
        ]
      };
    }

    if (styleKey === 'bay_exit') {
      // Start parked inside a deeper rear studio bay (~5.4m back in the distance),
      // wake up DRLs with ignition ripple, pull smoothly forward into (0, 0, 0) facing +Z
      // while camera pans smoothly into a sleek Side-Front Profile (±1.32 rad, ~±76°).
      var exitSide = (revSideOverride === -1 || revSideOverride === 1) ? revSideOverride : bayExitSide;
      var bayX = -2.45 * exitSide;
      var bayZ = -4.85;
      var bayYaw = 0.62 * exitSide;
      var exitAlpha = Math.abs(bayX) / Math.abs(Math.sin(bayYaw)); // intersection with x = 0 at Q = (0, -1.418)
      var exitQZ = bayZ + Math.cos(bayYaw) * exitAlpha;
      var exitP1 = {
        x: bayX + Math.sin(bayYaw) * (0.62 * exitAlpha),
        z: bayZ + Math.cos(bayYaw) * (0.62 * exitAlpha)
      };
      return {
        bayPose: { x: bayX, z: bayZ, yaw: bayYaw, fadeInEnd: 0.24, fadeOutStart: 2.00, fadeOutEnd: 2.62 },
        camProfile: {
          startTheta: 0.88 * exitSide,
          startPhi: 1.34,
          restTheta: 1.32 * exitSide,
          restPhi: 1.36,
          orbitStart: 0.42,
          orbitEnd: 2.24,
          offsetX: -0.45 * exitSide,
          offsetZ: -0.95,
          radiusBoost: 1.15,
          fadeInEnd: 0.0,
          returnStart: 0.42,
          returnEnd: 2.20
        },
        segments: [
          {
            type: 'hold',
            duration: 0.38,
            x: bayX,
            z: bayZ,
            yaw: bayYaw,
            steerTarget: 0.0,
            brakeStart: 0.35,
            brakeEnd: 0.0,
            headStart: 0.0,
            headEnd: 0.92,
            reverseStart: 0.0,
            reverseEnd: 0.0,
            wakeImpulse: [-0.025, 0.032, 0.014],
            wakeImpulseFired: false
          },
          {
            type: 'drive',
            dir: 1,
            duration: 1.86,
            ease: 'start_stop',
            p0: { x: bayX, z: bayZ },
            p1: exitP1,
            p2: { x: 0.0, z: 0.68 * exitQZ },
            p3: { x: 0.0, z: 0.0 },
            straightFadeStart: 0.78,
            headStart: 0.92,
            headEnd: 0.42,
            brakeStartT: 0.66,
            brakePeakT: 0.92,
            stopImpulse: [-0.080, 0.0, 0.011],
            stopFired: false
          },
          {
            type: 'hold',
            duration: 0.38,
            x: 0.0,
            z: 0.0,
            yaw: 0.0,
            steerTarget: 0.0,
            brakeStart: 0.80,
            brakeEnd: 0.0,
            headStart: 0.42,
            headEnd: 0.0,
            reverseStart: 0.0,
            reverseEnd: 0.0
          }
        ]
      };
    }

    // Default: 'right_drive' — crisp close-in lateral showroom curve from off-screen right
    // settling into classic Front-Left 3/4 (+0.76, 1.38).
    return {
      bayPose: null,
      camProfile: {
        startTheta: DEFAULT_THETA,
        startPhi: DEFAULT_PHI,
        restTheta: DEFAULT_THETA,
        restPhi: DEFAULT_PHI,
        orbitStart: 0.0,
        orbitEnd: 0.0,
        offsetX: 0,
        offsetZ: 0,
        radiusBoost: 0,
        fadeInEnd: 0,
        returnStart: 0,
        returnEnd: 0.01
      },
      segments: [
        {
          type: 'drive',
          dir: 1,
          duration: 1.72,
          ease: 'decel',
          p0: { x: rx * 7.8, z: rz * 7.8 - 1.2 },
          p1: { x: rx * 3.8, z: rz * 3.8 - 1.8 },
          p2: { x: 0.0, z: -2.45 },
          p3: { x: 0.0, z: 0.0 },
          straightFadeStart: 0.76,
          headStart: 0.65,
          headEnd: 0.36,
          brakeStartT: 0.18,
          brakePeakT: 0.45,
          stopImpulse: [-0.085, 0.0, 0.012],
          stopFired: false
        },
        {
          type: 'hold',
          duration: 0.28,
          x: 0.0,
          z: 0.0,
          yaw: 0.0,
          steerTarget: 0.0,
          brakeStart: 0.82,
          brakeEnd: 0.0,
          headStart: 0.36,
          headEnd: 0.0,
          reverseStart: 0.0,
          reverseEnd: 0.0
        }
      ]
    };
  }

  function applyEntranceCameraEnvelope(elapsedSec) {
    var cp = entrance.camProfile;
    if (!entrance.active || !cp) {
      entranceCam.offsetX = 0;
      entranceCam.offsetZ = 0;
      entranceCam.radiusBoost = 0;
      return;
    }
    var wIn = cp.fadeInEnd > 0 ? smoothstep(0, cp.fadeInEnd, elapsedSec) : 1.0;
    var wOut = cp.returnEnd > cp.returnStart
      ? (1.0 - smoothstep(cp.returnStart, cp.returnEnd, elapsedSec))
      : 0.0;
    var w = wIn * wOut;
    entranceCam.offsetX = (cp.offsetX || 0) * w;
    entranceCam.offsetZ = (cp.offsetZ || 0) * w;
    entranceCam.radiusBoost = (cp.radiusBoost || 0) * w;

    // Smoothly glide camera orbit angle along shortest arc into this choreography's showcase perspective
    if (typeof cp.startTheta === 'number' && typeof cp.restTheta === 'number' && !orbit.dragging && orbit.deckProgress <= 0.001) {
      var orbitT = (cp.orbitEnd > cp.orbitStart)
        ? smoothstep(cp.orbitStart, cp.orbitEnd, elapsedSec)
        : 1.0;
      var dTheta = wrapAngleDelta(cp.startTheta, cp.restTheta);
      var nextTheta = cp.startTheta + dTheta * orbitT;
      var nextPhi = lerp(cp.startPhi, cp.restPhi, orbitT);
      orbit.theta = nextTheta;
      orbit.targetTheta = nextTheta;
      orbit.phi = nextPhi;
      orbit.targetPhi = nextPhi;
    }
  }

  function startEntranceAnimation(styleOverride) {
    if (!currentContainer || hasCustomDemoAngle) return;
    var chosenStyle = resolveEntranceStyle(styleOverride);
    var sideForStyle = 1;
    if (chosenStyle === 'reverse_park') {
      sideForStyle = reverseParkSide;
      reverseParkSide = -reverseParkSide;
    } else if (chosenStyle === 'bay_exit') {
      sideForStyle = bayExitSide;
      bayExitSide = -bayExitSide;
    }
    var choreo = buildEntranceChoreography(chosenStyle, sideForStyle);
    var cp = choreo.camProfile || null;
    var initTheta = (cp && typeof cp.startTheta === 'number') ? cp.startTheta : DEFAULT_THETA;
    var initPhi = (cp && typeof cp.startPhi === 'number') ? cp.startPhi : DEFAULT_PHI;
    activeRestTheta = (cp && typeof cp.restTheta === 'number') ? cp.restTheta : DEFAULT_THETA;
    activeRestPhi = (cp && typeof cp.restPhi === 'number') ? cp.restPhi : DEFAULT_PHI;

    orbit.deckProgress = 0;
    orbit.topDownLocked = false;
    orbit.dragging = false;
    orbit.velTheta = 0;
    orbit.velPhi = 0;
    orbit.theta = initTheta;
    orbit.targetTheta = initTheta;
    orbit.phi = initPhi;
    orbit.targetPhi = initPhi;

    var totalDur = 0;
    for (var s = 0; s < choreo.segments.length; s++) {
      totalDur += choreo.segments[s].duration;
    }

    entrance.active = true;
    entrance.style = chosenStyle;
    entrance.elapsed = 0;
    entrance.totalDuration = totalDur;
    entrance.segments = choreo.segments;
    entrance.bayPose = choreo.bayPose;
    entrance.camProfile = cp;
    entrance.prevSegIdx = -1;
    entrance.wheelSpin = 0;
    entrance.steerAngle = 0;
    entrance.headGlow = 0;
    entrance.brakeGlow = 0;
    entrance.reverseGlow = 0;
    entrance.bayAlpha = 0;
    entrance.arrivalBlinkFired = false;

    applyEntranceCameraEnvelope(0);
    updateCameraPosition();

    if (parkingBay) {
      if (choreo.bayPose) {
        parkingBay.setPose(choreo.bayPose.x, choreo.bayPose.z, choreo.bayPose.yaw);
        parkingBay.setAlpha(0);
      } else {
        parkingBay.setAlpha(0);
      }
    }

    var firstSeg = choreo.segments[0];
    var initX = 0, initZ = 0, initYaw = 0;
    if (firstSeg.type === 'hold') {
      initX = firstSeg.x;
      initZ = firstSeg.z;
      initYaw = firstSeg.yaw;
      entrance.headGlow = firstSeg.headStart || 0;
      entrance.brakeGlow = firstSeg.brakeStart || 0;
    } else {
      initX = firstSeg.p0.x;
      initZ = firstSeg.p0.z;
      var d0 = evalCubicBezierDeriv(firstSeg.p0, firstSeg.p1, firstSeg.p2, firstSeg.p3, 0.0);
      initYaw = firstSeg.dir < 0 ? Math.atan2(-d0.x, -d0.z) : Math.atan2(d0.x, d0.z);
      entrance.headGlow = firstSeg.headStart || 0.65;
    }

    entrance.prevX = initX;
    entrance.prevZ = initZ;
    entrance.prevSpeed = 0;

    suspension.pitch = 0; suspension.pitchVel = 0;
    suspension.roll = 0; suspension.rollVel = 0;
    suspension.heave = 0; suspension.heaveVel = 0;

    currentContainer.position.set(initX, 0, initZ);
    currentContainer.rotation.set(0, initYaw, 0);
    if (currentRig) {
      currentRig.setSteering(0);
    }
  }

  function finishEntranceImmediately() {
    var hasCamOffset = Math.abs(entranceCam.offsetX) > 1e-4 ||
      Math.abs(entranceCam.offsetZ) > 1e-4 ||
      Math.abs(entranceCam.radiusBoost) > 1e-4;
    if (!entrance.active && entrance.bayAlpha <= 0.001 && entrance.reverseGlow <= 0.001 && !hasCamOffset) return;
    entrance.active = false;
    entrance.arrivalBlinkFired = true;
    entrance.headGlow = 0;
    entrance.brakeGlow = 0;
    entrance.reverseGlow = 0;
    entrance.bayAlpha = 0;
    entrance.steerAngle = 0;
    entrance.bayPose = null;
    entrance.camProfile = null;
    entranceCam.offsetX = 0;
    entranceCam.offsetZ = 0;
    entranceCam.radiusBoost = 0;
    if (parkingBay) {
      parkingBay.setAlpha(0);
    }
    if (currentContainer) {
      currentContainer.position.set(0, 0, 0);
      currentContainer.rotation.set(0, 0, 0);
    }
    if (currentRig) {
      currentRig.setSteering(0);
    }
    if (camera) {
      updateCameraPosition();
    }
  }

  var state = {
    modelKey: 'arrizo8',
    vehicleLocked: true,
    doorsOpen: false,
    trunkOpen: false,
    skylightOpen: false,
    skylightMode: 'closed',   // 'closed' | 'tilt' | 'open'
    windowsMode: 'closed',    // 'closed' | 'vent' | 'open'
    climateOn: false,
    climateTemp: 23.0,
    engineRunning: false
  };

  var orbit = {
    theta: DEFAULT_THETA,
    phi: DEFAULT_PHI,
    radius: HERO_RADIUS,
    targetTheta: DEFAULT_THETA,
    targetPhi: DEFAULT_PHI,
    targetRadius: HERO_RADIUS,
    targetCenter: new THREE.Vector3(0, CAR_CENTER_Y, 0.0),
    deckProgress: 0,
    deckStartTheta: DEFAULT_THETA,
    deckEndTheta: Math.PI,
    deckStartPhi: DEFAULT_PHI,
    velTheta: 0,
    velPhi: 0,
    lastMoveTime: 0,
    topDownLocked: false,
    dragging: false,
    startX: 0,
    startY: 0,
    lastX: 0,
    lastY: 0,
    movedDist: 0,
    lastTapTime: 0
  };

  function lerp(a, b, t) {
    return a + (b - a) * t;
  }

  function clamp(v, min, max) {
    return v < min ? min : (v > max ? max : v);
  }

  function smoothstep(edge0, edge1, x) {
    var t = clamp((x - edge0) / (edge1 - edge0), 0, 1);
    return t * t * (3 - 2 * t);
  }

  /**
   * Version-independent world-transform-preserving reparent helper.
   */
  function reparentKeepWorld(child, newParent) {
    if (!child || !newParent) return;
    child.updateMatrixWorld(true);
    newParent.updateMatrixWorld(true);
    var invParent = new THREE.Matrix4().copy(newParent.matrixWorld).invert();
    var localMat = new THREE.Matrix4().multiplyMatrices(invParent, child.matrixWorld);
    if (child.parent) {
      child.parent.remove(child);
    }
    newParent.add(child);
    localMat.decompose(child.position, child.quaternion, child.scale);
    child.updateMatrixWorld(true);
  }

  /**
   * Splits a multi-wheel merged BufferGeometry (all 4 wheels in one mesh) into
   * 4 clean quadrant BufferGeometries (FL, FR, RL, RR) in container-space so each
   * wheel can roll independently and front wheels can physically steer.
   */
  function splitWheelGeometryByQuadrant(mesh, zMid) {
    var src = mesh.geometry;
    if (!src || !src.attributes || !src.attributes.position) return null;
    mesh.updateMatrixWorld(true);
    var matWorld = mesh.matrixWorld;
    var normalMat = new THREE.Matrix3().getNormalMatrix(matWorld);

    var posAttr = src.attributes.position;
    var normAttr = src.attributes.normal || null;
    var uvAttr = src.attributes.uv || null;
    var indexAttr = src.index || null;

    var buckets = {
      FL: { pos: [], norm: [], uv: [] },
      FR: { pos: [], norm: [], uv: [] },
      RL: { pos: [], norm: [], uv: [] },
      RR: { pos: [], norm: [], uv: [] }
    };

    var v0 = new THREE.Vector3(), v1 = new THREE.Vector3(), v2 = new THREE.Vector3();
    var n0 = new THREE.Vector3(), n1 = new THREE.Vector3(), n2 = new THREE.Vector3();

    var triCount = indexAttr ? Math.floor(indexAttr.count / 3) : Math.floor(posAttr.count / 3);
    for (var t = 0; t < triCount; t++) {
      var i0 = indexAttr ? indexAttr.getX(t * 3) : t * 3;
      var i1 = indexAttr ? indexAttr.getX(t * 3 + 1) : t * 3 + 1;
      var i2 = indexAttr ? indexAttr.getX(t * 3 + 2) : t * 3 + 2;

      v0.fromBufferAttribute(posAttr, i0).applyMatrix4(matWorld);
      v1.fromBufferAttribute(posAttr, i1).applyMatrix4(matWorld);
      v2.fromBufferAttribute(posAttr, i2).applyMatrix4(matWorld);

      var cx = (v0.x + v1.x + v2.x) / 3;
      var cz = (v0.z + v1.z + v2.z) / 3;
      var key = (cz > zMid ? 'F' : 'R') + (cx < 0 ? 'L' : 'R');
      var b = buckets[key];

      b.pos.push(v0.x, v0.y, v0.z, v1.x, v1.y, v1.z, v2.x, v2.y, v2.z);

      if (normAttr) {
        n0.fromBufferAttribute(normAttr, i0).applyMatrix3(normalMat).normalize();
        n1.fromBufferAttribute(normAttr, i1).applyMatrix3(normalMat).normalize();
        n2.fromBufferAttribute(normAttr, i2).applyMatrix3(normalMat).normalize();
        b.norm.push(n0.x, n0.y, n0.z, n1.x, n1.y, n1.z, n2.x, n2.y, n2.z);
      }
      if (uvAttr) {
        b.uv.push(
          uvAttr.getX(i0), uvAttr.getY(i0),
          uvAttr.getX(i1), uvAttr.getY(i1),
          uvAttr.getX(i2), uvAttr.getY(i2)
        );
      }
    }

    return buckets;
  }

  function buildCenteredGeometry(bucket, center) {
    if (!bucket || bucket.pos.length === 0) return null;
    var geo = new THREE.BufferGeometry();
    var p = new Float32Array(bucket.pos.length);
    for (var i = 0; i < bucket.pos.length; i += 3) {
      p[i] = bucket.pos[i] - center.x;
      p[i + 1] = bucket.pos[i + 1] - center.y;
      p[i + 2] = bucket.pos[i + 2] - center.z;
    }
    geo.setAttribute('position', new THREE.BufferAttribute(p, 3));
    if (bucket.norm.length === bucket.pos.length) {
      geo.setAttribute('normal', new THREE.BufferAttribute(new Float32Array(bucket.norm), 3));
    } else {
      geo.computeVertexNormals();
    }
    if (bucket.uv.length > 0) {
      geo.setAttribute('uv', new THREE.BufferAttribute(new Float32Array(bucket.uv), 2));
    }
    return geo;
  }

  /**
   * Builds physically articulated sub-assemblies for both Arrizo 8 and Tiggo 8 GLBs:
   * - 4 independent rolling wheels (FL/FR with Ackermann steering knuckles + fixed brake calipers/discs)
   * - Real hinged rear trunk lid (Arrizo 8: ar8:mesh_1 .. ar8:mesh_1_7)
   * - Real panoramic sunroof panel (Arrizo 8: Door_Sunroof, Tiggo 8: tiggo8_sunroof)
   * - Real 4 side window glass panels (Arrizo 8: mesh_2_4, mesh_3_2, mesh_4_4, mesh_5_2)
   */
  function setupVehicleRig(container, chassisGroup, model, cfg) {
    container.updateMatrixWorld(true);

    var isTiggo8 = !!(cfg && cfg.key === 'tiggo8');
    var wheelMeshes = [];
    var trunkNodes = [];
    var windowNodes = [];
    var sunroofNode = null;

    var trunkNodeRe = /mesh_1($|_\d)/;
    // GLTFLoader strips ':' and '.' via sanitizeNodeName, turning 'mesh_0.001' into 'mesh_0001'
    var wheelMeshRe = /mesh_0(?:001|\.|_|$)/;
    var windowNodeRe = /mesh_(2_4|3_2|4_4|5_2)(_|$)/;

    model.traverse(function (obj) {
      var nm = obj.name || '';
      var parentNm = (obj.parent && obj.parent.name) ? obj.parent.name : '';
      if (isTiggo8) {
        if (!sunroofNode && (nm === 'tiggo8_sunroof' || parentNm === 'tiggo8_sunroof')) {
          sunroofNode = nm === 'tiggo8_sunroof' ? obj : obj.parent;
        }
        if (obj.isMesh && (nm.indexOf('tiggo8_wheel_') === 0 || parentNm.indexOf('tiggo8_wheel_') === 0)) {
          wheelMeshes.push(obj);
        }
      } else {
        if (!sunroofNode && nm.toLowerCase().indexOf('sunroof') !== -1 && !obj.isMesh) {
          sunroofNode = obj;
        }
        if (obj.isMesh && wheelMeshRe.test(nm)) {
          wheelMeshes.push(obj);
        }
        if (!obj.isMesh && trunkNodeRe.test(nm)) {
          trunkNodes.push(obj);
        }
        if (obj.isMesh && windowNodeRe.test(nm)) {
          windowNodes.push(obj);
        }
      }
    });

    // 1. Split 4 wheels out of chassisGroup and attach them directly to grounded container
    var wheelAssemblies = { FL: null, FR: null, RL: null, RR: null };
    var wheelRadius = DEFAULT_WHEEL_R;
    if (wheelMeshes.length > 0) {
      var allWheelsBox = new THREE.Box3();
      for (var w = 0; w < wheelMeshes.length; w++) {
        allWheelsBox.expandByObject(wheelMeshes[w]);
      }
      var zMid = (allWheelsBox.min.z + allWheelsBox.max.z) * 0.5;

      var splitEntries = [];
      var quadBounds = {
        FL: new THREE.Box3(),
        FR: new THREE.Box3(),
        RL: new THREE.Box3(),
        RR: new THREE.Box3()
      };

      for (var wm = 0; wm < wheelMeshes.length; wm++) {
        var mesh = wheelMeshes[wm];
        var buckets = splitWheelGeometryByQuadrant(mesh, zMid);
        if (!buckets) continue;
        var matName = (mesh.material && mesh.material.name) ? mesh.material.name : '';
        var nodeTag = (mesh.name || '') + ' ' + ((mesh.parent && mesh.parent.name) ? mesh.parent.name : '');
        var isCaliper = isTiggo8
          ? (nodeTag.indexOf('tiggo8_wheel_brake_') !== -1)
          : (mesh.name.indexOf('mesh_0_4') !== -1 && matName.indexOf('phong10SG1') !== -1);
        var isTire = isTiggo8
          ? (nodeTag.indexOf('tiggo8_wheel_tire_') !== -1 || matName.indexOf('Tire') !== -1)
          : (matName.indexOf('phong19SG1') !== -1);
        splitEntries.push({
          buckets: buckets,
          material: mesh.material,
          isCaliper: isCaliper,
          isTire: isTire
        });
        if (isTire) {
          ['FL', 'FR', 'RL', 'RR'].forEach(function (q) {
            var arr = buckets[q].pos;
            var pt = new THREE.Vector3();
            for (var p = 0; p < arr.length; p += 3) {
              pt.set(arr[p], arr[p + 1], arr[p + 2]);
              quadBounds[q].expandByPoint(pt);
            }
          });
        }
        mesh.visible = false;
      }

      // Fallback bounds if tire mesh wasn't matched by name
      ['FL', 'FR', 'RL', 'RR'].forEach(function (q) {
        if (quadBounds[q].isEmpty()) {
          for (var se = 0; se < splitEntries.length; se++) {
            var arr = splitEntries[se].buckets[q].pos;
            var pt = new THREE.Vector3();
            for (var p = 0; p < arr.length; p += 3) {
              pt.set(arr[p], arr[p + 1], arr[p + 2]);
              quadBounds[q].expandByPoint(pt);
            }
          }
        }
      });

      var sampleRadiusSum = 0;
      var sampleRadiusCount = 0;
      ['FL', 'FR', 'RL', 'RR'].forEach(function (q) {
        var b = quadBounds[q];
        if (b.isEmpty()) return;
        var center = new THREE.Vector3();
        b.getCenter(center);
        var rad = (b.max.y - b.min.y) * 0.5;
        if (rad > 0.15 && rad < 0.65) {
          sampleRadiusSum += rad;
          sampleRadiusCount++;
        }

        var steerPivot = new THREE.Group();
        steerPivot.name = 'wheel_steer_' + q;
        steerPivot.position.copy(center);
        container.add(steerPivot);

        var rollPivot = new THREE.Group();
        rollPivot.name = 'wheel_roll_' + q;
        steerPivot.add(rollPivot);

        for (var se = 0; se < splitEntries.length; se++) {
          var entry = splitEntries[se];
          var geo = buildCenteredGeometry(entry.buckets[q], center);
          if (!geo) continue;
          var partMesh = new THREE.Mesh(geo, entry.material);
          if (entry.isCaliper) {
            steerPivot.add(partMesh);
          } else {
            rollPivot.add(partMesh);
          }
        }
        wheelAssemblies[q] = { steerPivot: steerPivot, rollPivot: rollPivot };
      });
      if (sampleRadiusCount > 0) {
        wheelRadius = sampleRadiusSum / sampleRadiusCount;
      }
    }

    // 2. Real hinged rear trunk lid assembly (mesh_1 .. mesh_1_7)
    var trunkPivot = null;
    if (trunkNodes.length > 0) {
      var trunkBox = new THREE.Box3();
      for (var tn = 0; tn < trunkNodes.length; tn++) {
        trunkBox.expandByObject(trunkNodes[tn]);
      }
      if (!trunkBox.isEmpty()) {
        trunkPivot = new THREE.Group();
        trunkPivot.name = 'trunk_hinge_pivot';
        trunkPivot.position.set(
          (trunkBox.min.x + trunkBox.max.x) * 0.5,
          trunkBox.max.y - 0.015,
          trunkBox.max.z - 0.04
        );
        chassisGroup.add(trunkPivot);
        for (var tr = 0; tr < trunkNodes.length; tr++) {
          reparentKeepWorld(trunkNodes[tr], trunkPivot);
        }
      }
    }

    // 3. Real hinged/sliding sunroof glass panel (ar8:Door_Sunroof)
    var sunroofPivot = null;
    var sunroofPivotBase = null;
    if (sunroofNode) {
      var skyBox = new THREE.Box3().setFromObject(sunroofNode);
      if (!skyBox.isEmpty()) {
        sunroofPivot = new THREE.Group();
        sunroofPivot.name = 'sunroof_pivot';
        // Front hinge line so positive rotation.x lifts the rear edge cleanly for tilt!
        sunroofPivot.position.set(
          (skyBox.min.x + skyBox.max.x) * 0.5,
          skyBox.max.y - 0.012,
          skyBox.max.z - 0.02
        );
        sunroofPivotBase = sunroofPivot.position.clone();
        chassisGroup.add(sunroofPivot);
        reparentKeepWorld(sunroofNode, sunroofPivot);
      }
    }

    // 4. Real 4 side window glass panels (drop cleanly into door panels)
    var windowItems = [];
    for (var wi = 0; wi < windowNodes.length; wi++) {
      var wMesh = windowNodes[wi];
      var clonedMat = Array.isArray(wMesh.material)
        ? wMesh.material.map(function (m) { return m.clone(); })
        : wMesh.material.clone();
      wMesh.material = clonedMat;
      var baseOp = Array.isArray(clonedMat) ? clonedMat[0].opacity : clonedMat.opacity;
      windowItems.push({
        mesh: wMesh,
        baseY: wMesh.position.y,
        baseOpacity: typeof baseOp === 'number' ? baseOp : 0.28,
        // Convert world-meters drop into mesh parent's local units
        localPerMeter: 1.0 / Math.max(0.0001, wMesh.getWorldScale(new THREE.Vector3()).y)
      });
    }

    return {
      wheelRadius: wheelRadius,
      setWheelRoll: function (angleRad) {
        ['FL', 'FR', 'RL', 'RR'].forEach(function (q) {
          if (wheelAssemblies[q]) {
            wheelAssemblies[q].rollPivot.rotation.x = angleRad;
          }
        });
      },
      setSteering: function (centerSteerRad) {
        var clamped = clamp(centerSteerRad, -0.30, 0.30);
        // Subtle Ackermann inner/outer steering geometry (+X is driver-side left)
        var innerGain = 1.07;
        var outerGain = 0.94;
        var flAngle = clamped > 0 ? clamped * outerGain : clamped * innerGain;
        var frAngle = clamped > 0 ? clamped * innerGain : clamped * outerGain;
        if (wheelAssemblies.FL) wheelAssemblies.FL.steerPivot.rotation.y = flAngle;
        if (wheelAssemblies.FR) wheelAssemblies.FR.steerPivot.rotation.y = frAngle;
      },
      setTrunkOpenProgress: function (p) {
        if (!trunkPivot) return;
        // Positive X rotation lifts the rear (-Z) trunk lid upward ~54°
        trunkPivot.rotation.x = clamp(p, 0, 1) * 0.94;
      },
      setSunroofState: function (tiltP, slideP) {
        if (!sunroofPivot || !sunroofPivotBase) return;
        var tP = clamp(tiltP, 0, 1);
        var sP = clamp(slideP, 0, 1);
        sunroofPivot.rotation.x = tP * 0.17 + sP * 0.045;
        sunroofPivot.position.y = sunroofPivotBase.y + sP * 0.032 + tP * 0.004;
        sunroofPivot.position.z = sunroofPivotBase.z - sP * 0.36;
      },
      setWindowDropProgress: function (dropMeters) {
        var clampedDrop = clamp(dropMeters, 0, 0.34);
        var fadeFactor = 1.0 - smoothstep(0.18, 0.34, clampedDrop) * 0.88;
        for (var i = 0; i < windowItems.length; i++) {
          var item = windowItems[i];
          item.mesh.position.y = item.baseY - clampedDrop * item.localPerMeter;
          var mats = Array.isArray(item.mesh.material) ? item.mesh.material : [item.mesh.material];
          for (var m = 0; m < mats.length; m++) {
            mats[m].transparent = true;
            mats[m].opacity = item.baseOpacity * fadeFactor;
          }
        }
      }
    };
  }

  /**
   * High-contrast automotive photography studio HDR softbox environment map
   * so metallic paint, chrome grille, and tinted glass gleam cleanly on #161719.
   */
  function createStudioEnvironment(webglRenderer) {
    var pmrem = new THREE.PMREMGenerator(webglRenderer);
    pmrem.compileEquirectangularShader();

    var envScene = new THREE.Scene();
    envScene.background = new THREE.Color(0x1f2430);

    var topBox = new THREE.Mesh(
      new THREE.PlaneGeometry(16, 24),
      new THREE.MeshBasicMaterial({ color: 0xffffff, side: THREE.DoubleSide })
    );
    topBox.position.set(0, 7.2, 0);
    topBox.rotation.x = Math.PI / 2;
    envScene.add(topBox);

    var leftStrip = new THREE.Mesh(
      new THREE.PlaneGeometry(18, 3.6),
      new THREE.MeshBasicMaterial({ color: 0xf4f8ff, side: THREE.DoubleSide })
    );
    leftStrip.position.set(7.2, 2.8, 1.5);
    leftStrip.rotation.y = -Math.PI / 2;
    envScene.add(leftStrip);

    var rightStrip = new THREE.Mesh(
      new THREE.PlaneGeometry(18, 3.2),
      new THREE.MeshBasicMaterial({ color: 0xe2ecff, side: THREE.DoubleSide })
    );
    rightStrip.position.set(-7.2, 2.6, -1.0);
    rightStrip.rotation.y = Math.PI / 2;
    envScene.add(rightStrip);

    var frontBar = new THREE.Mesh(
      new THREE.PlaneGeometry(14, 2.8),
      new THREE.MeshBasicMaterial({ color: 0xffffff, side: THREE.DoubleSide })
    );
    frontBar.position.set(0, 2.2, 9.0);
    envScene.add(frontBar);

    var rearBar = new THREE.Mesh(
      new THREE.PlaneGeometry(14, 2.4),
      new THREE.MeshBasicMaterial({ color: 0xdbe6f8, side: THREE.DoubleSide })
    );
    rearBar.position.set(0, 2.4, -9.0);
    envScene.add(rearBar);

    var lowerRing = new THREE.Mesh(
      new THREE.CylinderGeometry(10, 10, 1.4, 32, 1, true),
      new THREE.MeshBasicMaterial({ color: 0x6b778c, side: THREE.BackSide })
    );
    lowerRing.position.y = 0.5;
    envScene.add(lowerRing);

    var rt = pmrem.fromScene(envScene, 0.03);
    pmrem.dispose();
    return rt.texture;
  }

  function createContactShadowTexture() {
    var canvas = document.createElement('canvas');
    canvas.width = 512;
    canvas.height = 1024;
    var ctx = canvas.getContext('2d');
    ctx.clearRect(0, 0, canvas.width, canvas.height);

    ctx.save();
    ctx.translate(256, 512);
    ctx.scale(1.0, 2.02);
    var g1 = ctx.createRadialGradient(0, 0, 50, 0, 0, 240);
    g1.addColorStop(0.0, 'rgba(0, 0, 0, 0.92)');
    g1.addColorStop(0.45, 'rgba(0, 0, 0, 0.65)');
    g1.addColorStop(0.75, 'rgba(0, 0, 0, 0.20)');
    g1.addColorStop(1.0, 'rgba(0, 0, 0, 0.0)');
    ctx.fillStyle = g1;
    ctx.beginPath();
    ctx.arc(0, 0, 240, 0, Math.PI * 2);
    ctx.fill();
    ctx.restore();

    ctx.save();
    ctx.translate(256, 512);
    ctx.scale(0.82, 1.88);
    var g2 = ctx.createRadialGradient(0, 0, 20, 0, 0, 185);
    g2.addColorStop(0.0, 'rgba(0, 0, 0, 0.96)');
    g2.addColorStop(0.72, 'rgba(0, 0, 0, 0.72)');
    g2.addColorStop(1.0, 'rgba(0, 0, 0, 0.0)');
    ctx.fillStyle = g2;
    ctx.beginPath();
    ctx.arc(0, 0, 185, 0, Math.PI * 2);
    ctx.fill();
    ctx.restore();

    var tex = new THREE.CanvasTexture(canvas);
    tex.anisotropy = 4;
    return tex;
  }

  function createSleekLightBarTexture(coreRgb, edgeRgb) {
    var canvas = document.createElement('canvas');
    canvas.width = 256;
    canvas.height = 64;
    var ctx = canvas.getContext('2d');
    ctx.clearRect(0, 0, 256, 64);

    ctx.save();
    ctx.translate(128, 32);
    ctx.scale(1.0, 0.24);
    var grad = ctx.createRadialGradient(0, 0, 4, 0, 0, 118);
    grad.addColorStop(0.0, 'rgba(' + coreRgb + ', 1.0)');
    grad.addColorStop(0.32, 'rgba(' + coreRgb + ', 0.92)');
    grad.addColorStop(0.65, 'rgba(' + edgeRgb + ', 0.36)');
    grad.addColorStop(1.0, 'rgba(' + edgeRgb + ', 0.0)');
    ctx.fillStyle = grad;
    ctx.beginPath();
    ctx.arc(0, 0, 118, 0, Math.PI * 2);
    ctx.fill();
    ctx.restore();

    return new THREE.CanvasTexture(canvas);
  }

  // Automotive LED hazard pulse envelope:
  // ~45ms snap-on -> ~130ms plateau -> ~150ms optical afterglow fade -> ~150ms clean dark pause
  var HAZARD_PULSE_RISE = 0.045;
  var HAZARD_PULSE_HOLD = 0.130;
  var HAZARD_PULSE_DECAY = 0.150;
  var HAZARD_PULSE_GAP = 0.150;
  var HAZARD_PULSE_ACTIVE = HAZARD_PULSE_RISE + HAZARD_PULSE_HOLD + HAZARD_PULSE_DECAY; // 0.325s
  var HAZARD_PULSE_STRIDE = HAZARD_PULSE_ACTIVE + HAZARD_PULSE_GAP; // 0.475s

  function hazardTotalDuration(pulseCount) {
    var n = Math.max(1, Math.min(4, Math.round(pulseCount || 2)));
    return (n - 1) * HAZARD_PULSE_STRIDE + HAZARD_PULSE_ACTIVE;
  }

  function sampleHazardPulse(tSec, pulseCount) {
    var n = Math.max(1, Math.min(4, Math.round(pulseCount || 2)));
    var total = hazardTotalDuration(n);
    if (tSec < 0 || tSec >= total) return 0;
    var idx = Math.floor(tSec / HAZARD_PULSE_STRIDE);
    if (idx >= n) return 0;
    var local = tSec - idx * HAZARD_PULSE_STRIDE;
    if (local < 0 || local >= HAZARD_PULSE_ACTIVE) return 0;
    if (local < HAZARD_PULSE_RISE) {
      var uRise = local / HAZARD_PULSE_RISE;
      return 1.0 - Math.pow(1.0 - uRise, 2.2);
    }
    if (local < HAZARD_PULSE_RISE + HAZARD_PULSE_HOLD) {
      return 1.0;
    }
    var uDecay = (local - HAZARD_PULSE_RISE - HAZARD_PULSE_HOLD) / HAZARD_PULSE_DECAY;
    return Math.pow(Math.max(0, 1.0 - uDecay), 2.1);
  }

  function resolveClimateColorHex(tempC) {
    var t = typeof tempC === 'number' && isFinite(tempC) ? tempC : 23.0;
    if (t <= 21.0) return 0x38bdf8;   // Crisp icy cyan-blue
    if (t <= 23.5) return 0x60a5fa;   // Cool sky blue
    if (t <= 25.0) return 0xb8d4f8;   // Neutral comfort mist
    return 0xfb923c;                  // Warm cabin amber
  }

  function createInteractiveOverlays(chassisRoot, gltfScene, rig, cfg, modelSize) {
    var headBarTex = createSleekLightBarTexture('240, 247, 255', '196, 224, 255');
    var tailBarTex = createSleekLightBarTexture('255, 42, 42', '255, 30, 30');

    var headPos = (cfg && cfg.headlightPos) ? cfg.headlightPos : [0.67, 0.77, 2.16];
    var tailPos = (cfg && cfg.taillightPos) ? cfg.taillightPos : [0.66, 0.90, -2.26];
    var hornPos = (cfg && cfg.hornPos) ? cfg.hornPos : [0, 0.66, 2.38];
    var heightLift = modelSize ? Math.max(0, modelSize.y - 1.48) : 0;

    var headBarSprites = [];
    var tailBarSprites = [];
    var reverseBarSprites = [];

    for (var side = -1; side <= 1; side += 2) {
      var hb = new THREE.Sprite(new THREE.SpriteMaterial({
        map: headBarTex,
        color: 0xf0f7ff,
        transparent: true,
        opacity: 0.0,
        blending: THREE.AdditiveBlending,
        depthWrite: false
      }));
      hb.position.set(side * headPos[0], headPos[1], headPos[2]);
      hb.scale.set(0.34, 0.085, 1);
      chassisRoot.add(hb);
      headBarSprites.push(hb);

      var tb = new THREE.Sprite(new THREE.SpriteMaterial({
        map: tailBarTex,
        color: 0xff1e1e,
        transparent: true,
        opacity: 0.0,
        blending: THREE.AdditiveBlending,
        depthWrite: false
      }));
      tb.position.set(side * tailPos[0], tailPos[1], tailPos[2]);
      tb.scale.set(0.34, 0.075, 1);
      chassisRoot.add(tb);
      tailBarSprites.push(tb);

      // Subtle cool-white rear reverse-light lens glow (illuminates during reverse_park)
      var rb = new THREE.Sprite(new THREE.SpriteMaterial({
        map: headBarTex,
        color: 0xf5faff,
        transparent: true,
        opacity: 0.0,
        blending: THREE.AdditiveBlending,
        depthWrite: false
      }));
      rb.position.set(side * tailPos[0] * 0.66, tailPos[1] - 0.045, tailPos[2] - 0.015);
      rb.scale.set(0.24, 0.062, 1);
      chassisRoot.add(rb);
      reverseBarSprites.push(rb);
    }

    // 3D climate airflow ribbons (dynamically tinted by target cabin temperature)
    var airflowGroup = new THREE.Group();
    airflowGroup.visible = false;
    var ribbons = [];
    for (var i = 0; i < 3; i++) {
      var xOff = (i - 1) * 0.34;
      var curve = new THREE.CatmullRomCurve3([
        new THREE.Vector3(xOff, 1.12 + heightLift * 0.75, 0.95),
        new THREE.Vector3(xOff * 0.95, 1.62 + heightLift * 0.92, 0.25),
        new THREE.Vector3(xOff * 0.92, 1.68 + heightLift * 0.92, -0.50),
        new THREE.Vector3(xOff * 0.98, 1.48 + heightLift * 0.85, -1.25)
      ]);
      var mat = new THREE.MeshBasicMaterial({
        color: 0x60a5fa,
        transparent: true,
        opacity: 0.55,
        blending: THREE.AdditiveBlending,
        depthWrite: false
      });
      var mesh = new THREE.Mesh(new THREE.TubeGeometry(curve, 36, 0.013, 8, false), mat);
      airflowGroup.add(mesh);
      ribbons.push({ mesh: mesh, mat: mat, phaseOffset: i * 1.1 });
    }
    chassisRoot.add(airflowGroup);

    // Subtle rising side-window ventilation airflow streaks (visible during window vent mode)
    var ventGroup = new THREE.Group();
    ventGroup.visible = false;
    var ventStreaks = [];
    for (var vs = -1; vs <= 1; vs += 2) {
      for (var vz = 0; vz < 2; vz++) {
        var zPos = vz === 0 ? 0.36 : -0.48;
        var vCurve = new THREE.CatmullRomCurve3([
          new THREE.Vector3(vs * 0.74, 1.24 + heightLift * 0.78, zPos),
          new THREE.Vector3(vs * 0.88, 1.40 + heightLift * 0.82, zPos - 0.14),
          new THREE.Vector3(vs * 0.95, 1.56 + heightLift * 0.86, zPos - 0.32)
        ]);
        var vMat = new THREE.MeshBasicMaterial({
          color: 0xa5d8ff,
          transparent: true,
          opacity: 0.0,
          blending: THREE.AdditiveBlending,
          depthWrite: false
        });
        var vMesh = new THREE.Mesh(new THREE.TubeGeometry(vCurve, 20, 0.008, 6, false), vMat);
        ventGroup.add(vMesh);
        ventStreaks.push({ mesh: vMesh, mat: vMat, phase: (vs > 0 ? 0 : 1.4) + vz * 0.85 });
      }
    }
    chassisRoot.add(ventGroup);

    // Subtle expanding 3D acoustic horn wave rings from front grille for Find-Car
    var hornGroup = new THREE.Group();
    hornGroup.visible = false;
    var hornRings = [];
    for (var hr = 0; hr < 2; hr++) {
      var ringGeo = new THREE.RingGeometry(0.18, 0.215, 48);
      var ringMat = new THREE.MeshBasicMaterial({
        color: 0xdbeafe,
        transparent: true,
        opacity: 0.0,
        side: THREE.DoubleSide,
        blending: THREE.AdditiveBlending,
        depthWrite: false
      });
      var ringMesh = new THREE.Mesh(ringGeo, ringMat);
      ringMesh.position.set(hornPos[0], hornPos[1], hornPos[2]);
      hornGroup.add(ringMesh);
      hornRings.push({ mesh: ringMesh, mat: ringMat, delay: hr * 0.24, baseZ: hornPos[2] });
    }
    chassisRoot.add(hornGroup);

    // Locate front headlamp/DRL and rear taillight materials inside the real GLB
    var headMats = [];
    var tailMats = [];
    gltfScene.traverse(function (obj) {
      if (obj.isMesh && obj.material) {
        var nm = obj.name || '';
        var pnm = (obj.parent && obj.parent.name) ? obj.parent.name : '';
        var isFrontLampMesh = nm.indexOf('mesh_9_ar8') !== -1 ||
          nm === 'tiggo8_headlamp_led' || pnm === 'tiggo8_headlamp_led';
        if (isFrontLampMesh && !Array.isArray(obj.material)) {
          obj.material = obj.material.clone();
          headMats.push(obj.material);
        }
        var mats = Array.isArray(obj.material) ? obj.material : [obj.material];
        for (var m = 0; m < mats.length; m++) {
          var mt = mats[m];
          if (mt.name === 'Material #159' && headMats.indexOf(mt) === -1) {
            headMats.push(mt);
          }
          // Exclude red brake caliper material (mesh_0_4) so calipers never glow like taillights
          var isCaliperMesh = nm.indexOf('mesh_0_4') !== -1 || nm.indexOf('tiggo8_wheel_brake_') !== -1;
          var isRedTail = !isCaliperMesh && (
            (mt.name && (mt.name.indexOf('phong10SG1') !== -1 || mt.name === 'Material #162' || mt.name === 'GlassRed')) ||
            nm === 'tiggo8_taillamp_led' || pnm === 'tiggo8_taillamp_led' ||
            (mt.color && mt.color.r > 0.4 && mt.color.g < 0.12 && mt.color.b < 0.12)
          );
          if (isRedTail && tailMats.indexOf(mt) === -1) {
            tailMats.push(mt);
          }
        }
      }
    });

    var anim = {
      flashActive: false,
      flashElapsed: 0,
      flashPulses: 2,
      flashLevel: 0,
      hornActive: false,
      hornElapsed: 0,
      trunkProgress: 0,
      sunroofTilt: 0,
      sunroofSlide: 0,
      windowDrop: 0,
      prevTrunkOpen: false,
      prevEngineRunning: false,
      prevLocked: true
    };

    function startHazardBlink(pulses) {
      anim.flashActive = true;
      anim.flashElapsed = 0;
      anim.flashPulses = Math.max(1, Math.min(4, Math.round(pulses || 2)));
    }

    function triggerFeedback(actionKey) {
      switch (actionKey) {
        case 'find_car':
          startHazardBlink(3);
          anim.hornActive = true;
          anim.hornElapsed = 0;
          impulseSuspension(0.0, 0.0, 0.018);
          break;
        case 'unlock':
          startHazardBlink(2);
          impulseSuspension(0.0, 0.0, 0.024);
          break;
        case 'lock':
          startHazardBlink(1);
          impulseSuspension(0.0, 0.0, -0.022);
          break;
        case 'engine_start':
          startHazardBlink(1);
          // Realistic ignition torque roll & brief chassis thrum
          impulseSuspension(-0.045, 0.075, 0.032);
          break;
        case 'engine_stop':
          impulseSuspension(0.022, -0.035, -0.018);
          break;
        case 'trunk_open':
          startHazardBlink(1);
          impulseSuspension(-0.05, 0.0, 0.024);
          break;
        case 'tailgate_close':
          impulseSuspension(0.065, 0.0, -0.036);
          break;
        case 'window_ventilate':
        case 'window_open':
        case 'window_close':
        case 'skylight_open':
        case 'skylight_tilt':
        case 'skylight_close':
          impulseSuspension(0.0, 0.0, 0.014);
          break;
        case 'ac_on':
        case 'ac_off':
          impulseSuspension(0.0, 0.0, 0.012);
          break;
        default:
          startHazardBlink(2);
          break;
      }
    }

    return {
      triggerFlash: function () {
        startHazardBlink(2);
      },
      triggerHazardBlink: function (pulses) {
        startHazardBlink(typeof pulses === 'number' ? pulses : 2);
      },
      triggerFeedback: triggerFeedback,
      update: function (dt, t, st) {
        var smooth = 1.0 - Math.exp(-dt * 6.8);
        var slowSmooth = 1.0 - Math.exp(-dt * 4.5);

        // React physically to telemetry state transitions as well
        if (st.trunkOpen !== anim.prevTrunkOpen) {
          anim.prevTrunkOpen = st.trunkOpen;
          impulseSuspension(st.trunkOpen ? -0.045 : 0.055, 0.0, st.trunkOpen ? 0.02 : -0.03);
        }
        if (st.engineRunning !== anim.prevEngineRunning) {
          anim.prevEngineRunning = st.engineRunning;
          if (st.engineRunning) {
            impulseSuspension(-0.04, 0.07, 0.028);
          } else {
            impulseSuspension(0.02, -0.03, -0.015);
          }
        }
        if (st.vehicleLocked !== anim.prevLocked) {
          anim.prevLocked = st.vehicleLocked;
          startHazardBlink(st.vehicleLocked ? 1 : 2);
        }

        // Automotive LED hazard double/triple-flash cadence (drives real headlamp + taillight only)
        if (anim.flashActive) {
          anim.flashElapsed += dt;
          var maxDur = hazardTotalDuration(anim.flashPulses);
          if (anim.flashElapsed >= maxDur) {
            anim.flashActive = false;
            anim.flashLevel = 0;
          } else {
            anim.flashLevel = sampleHazardPulse(anim.flashElapsed, anim.flashPulses);
          }
        } else {
          anim.flashLevel = 0;
        }

        // Front pure cool-white (#F0F7FF) headlamp/DRL emissive + LED flash pulse
        var headRest = Math.max(st.engineRunning ? 0.24 : 0.0, entrance.headGlow * 0.55);
        var headLevel = Math.min(1.0, headRest + anim.flashLevel);
        for (var hm = 0; hm < headMats.length; hm++) {
          if (headMats[hm].emissive) {
            headMats[hm].emissive.setHex(0xf0f7ff);
            headMats[hm].emissiveIntensity = headLevel * 1.45;
          }
        }
        var headBarOp = anim.flashLevel * 0.92 + Math.max(st.engineRunning ? 0.26 : 0.0, entrance.headGlow * 0.52);
        for (var hbIdx = 0; hbIdx < headBarSprites.length; hbIdx++) {
          headBarSprites[hbIdx].material.opacity = clamp(headBarOp, 0, 0.95);
        }

        // Rear pure red (#FF1E1E) taillight + braking glow + LED flash pulse
        var tailBase = (st.engineRunning ? 0.30 : 0.0) + (st.vehicleLocked ? 0.0 : 0.12);
        var brakeBoost = entrance.brakeGlow * 1.35;
        var tailIntensity = 0.15 + tailBase + brakeBoost + anim.flashLevel * 1.65;
        for (var tm = 0; tm < tailMats.length; tm++) {
          if (tailMats[tm].emissive) {
            tailMats[tm].emissive.setHex(0xff1e1e);
            tailMats[tm].emissiveIntensity = tailIntensity;
          }
        }
        var tailBarOp = anim.flashLevel * 0.68 + entrance.brakeGlow * 0.62 + (st.engineRunning ? 0.14 : 0.0);
        for (var tbIdx = 0; tbIdx < tailBarSprites.length; tbIdx++) {
          tailBarSprites[tbIdx].material.opacity = clamp(tailBarOp, 0, 0.85);
        }
        var revBarOp = clamp(entrance.reverseGlow * 0.88, 0, 0.92);
        for (var rbIdx = 0; rbIdx < reverseBarSprites.length; rbIdx++) {
          reverseBarSprites[rbIdx].material.opacity = revBarOp;
        }

        // Expanding 3D acoustic horn wave rings
        if (anim.hornActive) {
          anim.hornElapsed += dt;
          hornGroup.visible = true;
          var anyAlive = false;
          for (var rIdx = 0; rIdx < hornRings.length; rIdx++) {
            var hrObj = hornRings[rIdx];
            var localT = (anim.hornElapsed - hrObj.delay) / 0.68;
            if (localT >= 0 && localT <= 1.0) {
              anyAlive = true;
              var ease = 1.0 - Math.pow(1.0 - localT, 2.4);
              var sc = 1.0 + ease * 5.2;
              hrObj.mesh.scale.set(sc, sc * 0.72, 1);
              hrObj.mesh.position.z = (hrObj.baseZ || 2.38) + ease * 0.85;
              hrObj.mat.opacity = Math.sin(localT * Math.PI) * 0.42;
            } else {
              hrObj.mat.opacity = 0;
            }
          }
          if (!anyAlive && anim.hornElapsed > 0.95) {
            anim.hornActive = false;
            hornGroup.visible = false;
          }
        }

        // Articulated real 3D trunk lid
        var targetTrunk = st.trunkOpen ? 1.0 : 0.0;
        anim.trunkProgress = lerp(anim.trunkProgress, targetTrunk, slowSmooth);
        if (rig) rig.setTrunkOpenProgress(anim.trunkProgress);

        // Articulated real 3D sunroof (tilt vs slide open vs closed)
        var effectiveSkyMode = st.skylightMode || (st.skylightOpen ? 'open' : 'closed');
        var targetTilt = effectiveSkyMode === 'tilt' ? 1.0 : 0.0;
        var targetSlide = effectiveSkyMode === 'open' ? 1.0 : 0.0;
        anim.sunroofTilt = lerp(anim.sunroofTilt, targetTilt, smooth);
        anim.sunroofSlide = lerp(anim.sunroofSlide, targetSlide, slowSmooth);
        if (rig) rig.setSunroofState(anim.sunroofTilt, anim.sunroofSlide);

        // Articulated 4 real side windows (closed / vent 4.5cm / open 30cm)
        var targetDrop = st.windowsMode === 'open' ? 0.30 : (st.windowsMode === 'vent' ? 0.052 : 0.0);
        anim.windowDrop = lerp(anim.windowDrop, targetDrop, slowSmooth);
        if (rig) rig.setWindowDropProgress(anim.windowDrop);

        // Side-window ventilation breeze streaks
        var showVent = st.windowsMode === 'vent';
        ventGroup.visible = showVent;
        if (showVent) {
          for (var vi = 0; vi < ventStreaks.length; vi++) {
            var vsObj = ventStreaks[vi];
            var vp = 0.5 + 0.5 * Math.sin(t * 2.8 + vsObj.phase);
            vsObj.mat.opacity = 0.14 + vp * 0.32;
            vsObj.mesh.position.y = Math.sin(t * 2.2 + vsObj.phase) * 0.018;
          }
        }

        // Climate 3D airflow ribbons tinted by temperature
        airflowGroup.visible = !!st.climateOn;
        if (st.climateOn) {
          var climHex = resolveClimateColorHex(st.climateTemp);
          for (var r = 0; r < ribbons.length; r++) {
            var rb = ribbons[r];
            rb.mat.color.setHex(climHex);
            var pulse = 0.5 + 0.5 * Math.sin(t * 3.4 + rb.phaseOffset);
            rb.mat.opacity = 0.22 + pulse * 0.48;
            rb.mesh.position.y = Math.sin(t * 2.2 + rb.phaseOffset) * 0.03;
          }
        }
      }
    };
  }

  function disposeObject3D(root) {
    if (!root) return;
    var disposedGeos = new Set();
    var disposedMats = new Set();
    var disposedTexs = new Set();
    var texKeys = ['map', 'normalMap', 'roughnessMap', 'metalnessMap', 'aoMap', 'emissiveMap', 'alphaMap', 'bumpMap'];
    root.traverse(function (obj) {
      if (obj.geometry && !disposedGeos.has(obj.geometry)) {
        disposedGeos.add(obj.geometry);
        if (typeof obj.geometry.dispose === 'function') obj.geometry.dispose();
      }
      if (obj.material) {
        var mats = Array.isArray(obj.material) ? obj.material : [obj.material];
        for (var i = 0; i < mats.length; i++) {
          var mat = mats[i];
          if (!mat || disposedMats.has(mat)) continue;
          disposedMats.add(mat);
          for (var k = 0; k < texKeys.length; k++) {
            var tex = mat[texKeys[k]];
            if (tex && tex !== envMap && !disposedTexs.has(tex)) {
              disposedTexs.add(tex);
              if (typeof tex.dispose === 'function') tex.dispose();
            }
          }
          if (typeof mat.dispose === 'function') mat.dispose();
        }
      }
    });
  }

  function loadModel(modelKey, customGlbUrl) {
    var cfg = global.CarModelRegistry.get(modelKey);
    var resolvedUrl = (typeof customGlbUrl === 'string' && customGlbUrl) ? customGlbUrl : cfg.glbUrl;
    if (currentModelKey === cfg.key && activeLoadedUrl === resolvedUrl && currentContainer) {
      return;
    }
    currentModelKey = cfg.key;
    activeLoadedUrl = resolvedUrl;
    state.modelKey = cfg.key;
    WHEELBASE_M = cfg.wheelbase || 2.77;

    if (currentContainer) {
      scene.remove(currentContainer);
      disposeObject3D(currentContainer);
      currentContainer = null;
      currentChassis = null;
      currentEffects = null;
      currentRig = null;
      currentModelSize = null;
    }
    if (parkingBay) {
      scene.remove(parkingBay.group);
      disposeObject3D(parkingBay.group);
      parkingBay = null;
    }

    var container = new THREE.Group();
    container.name = 'car_container_' + cfg.key;
    // Keep hidden until GLB finishes parsing & shader pre-compilation so reveal is silky smooth
    container.visible = false;
    currentContainer = container;
    scene.add(container);

    // Sprung chassis group sits inside container so suspension moves body while wheels stay on Y=0
    var chassisGroup = new THREE.Group();
    chassisGroup.name = 'car_chassis_' + cfg.key;
    currentChassis = chassisGroup;
    container.add(chassisGroup);

    // Contact shadow on Y=0 follows vehicle position & yaw cleanly
    var shadowTex = createContactShadowTexture();
    var shadowPlane = new THREE.Mesh(
      new THREE.PlaneGeometry(3.05, 6.05).rotateX(-Math.PI / 2),
      new THREE.MeshBasicMaterial({ map: shadowTex, transparent: true, opacity: 0.94, depthWrite: false })
    );
    shadowPlane.position.y = 0.002;
    container.add(shadowPlane);

    gltfLoader.load(
      resolvedUrl,
      function (gltf) {
        if (currentContainer !== container) return;
        var model = gltf.scene;
        var isTiggo8 = cfg.key === 'tiggo8';

        model.traverse(function (child) {
          if (child.isMesh && child.material) {
            var mats = Array.isArray(child.material) ? child.material : [child.material];
            for (var i = 0; i < mats.length; i++) {
              var mat = mats[i];
              mat.envMap = envMap;
              mat.envMapIntensity = 1.65;
              if (isTiggo8) {
                var mn = mat.name || '';
                if (mn === 'Car paint') {
                  mat.metalness = 0.36;
                  mat.roughness = 0.18;
                  mat.envMapIntensity = 1.95;
                } else if (mn === 'MetallicWhite') {
                  mat.metalness = 0.90;
                  mat.roughness = 0.24;
                  if (mat.color) mat.color.setHex(0xd2d8e4);
                  mat.envMapIntensity = 2.05;
                } else if (mn === 'GlassBlack') {
                  if (mat.color) mat.color.setHex(0x12161d);
                  mat.metalness = 0.85;
                  mat.roughness = 0.08;
                  mat.opacity = 0.78;
                  mat.transparent = true;
                  mat.envMapIntensity = 1.8;
                } else if (mn === 'GlassRed') {
                  if (mat.color) mat.color.setHex(0xc81616);
                  mat.roughness = 0.14;
                  mat.opacity = 0.82;
                  mat.transparent = true;
                } else if (mn === 'MetallicBlack02') {
                  mat.roughness = 0.28;
                  mat.envMapIntensity = 1.5;
                }
              } else if (mat.metalness > 0.65 && mat.roughness < 0.12 && mat.color) {
                mat.envMapIntensity = 2.1;
              }
              mat.needsUpdate = true;
            }
          }
        });

        var box = new THREE.Box3().setFromObject(model);
        var size = new THREE.Vector3();
        box.getSize(size);

        if (size.x > size.z * 1.15) {
          model.rotation.y += Math.PI / 2;
        }
        if (cfg.yawOffset) {
          model.rotation.y += cfg.yawOffset;
        }

        model.updateMatrixWorld(true);
        box.setFromObject(model);
        box.getSize(size);
        var maxHorizontal = Math.max(size.x, size.z, 0.001);
        var targetLen = cfg.targetLength || 4.78;
        var scale = targetLen / maxHorizontal;
        model.scale.multiplyScalar(scale);

        model.updateMatrixWorld(true);
        box.setFromObject(model);
        box.getSize(size);
        var center = new THREE.Vector3();
        box.getCenter(center);
        model.position.x -= center.x;
        model.position.z -= center.z;
        model.position.y -= box.min.y;

        // Optically center both sleek sedans and taller SUVs on stage
        orbit.targetCenter.y = clamp(size.y * 0.42, 0.56, 0.76);
        shadowPlane.scale.set(
          clamp((size.x * 1.48) / 3.05, 0.85, 1.35),
          1,
          clamp((size.z * 1.24) / 6.05, 0.85, 1.25)
        );

        chassisGroup.add(model);
        currentModelSize = size.clone();
        if (parkingBay) {
          scene.remove(parkingBay.group);
          disposeObject3D(parkingBay.group);
        }
        parkingBay = createParkingBay(currentModelSize);
        scene.add(parkingBay.group);

        currentRig = setupVehicleRig(container, chassisGroup, model, cfg);
        currentEffects = createInteractiveOverlays(chassisGroup, model, currentRig, cfg, size);
        resize();

        if (!hasCustomDemoAngle) {
          startEntranceAnimation();
        }
        container.visible = true;

        // Pre-compile WebGL shaders before first visible frame to prevent mid-animation stutter
        try {
          if (renderer && scene && camera) {
            renderer.compile(scene, camera);
            renderer.render(scene, camera);
          }
        } catch (compileErr) {
          // ignore shader pre-compile edge errors
        }

        notifyAndroidReady();
        requestAnimationFrame(function () {
          requestAnimationFrame(function () {
            if (renderer && scene && camera) {
              renderer.render(scene, camera);
            }
            notifyAndroidFirstFrame();
          });
        });
      },
      undefined,
      function (err) {
        if (global.Car3DBridge && typeof global.Car3DBridge.onWebGLError === 'function') {
          global.Car3DBridge.onWebGLError('GLB load failed: ' + String(err));
        }
      }
    );
  }

  function updateCameraPosition() {
    var sinPhi = Math.sin(orbit.phi);
    var effRadius = orbit.radius + entranceCam.radiusBoost;
    var cx = orbit.targetCenter.x + entranceCam.offsetX;
    var cy = orbit.targetCenter.y;
    var cz = orbit.targetCenter.z + entranceCam.offsetZ;
    camera.position.set(
      cx + effRadius * sinPhi * Math.sin(orbit.theta),
      cy + effRadius * Math.cos(orbit.phi),
      cz + effRadius * sinPhi * Math.cos(orbit.theta)
    );
    camera.lookAt(cx, cy, cz);
  }

  function notifyAndroidTap() {
    try {
      if (global.Car3DBridge && typeof global.Car3DBridge.onVehicleTap === 'function') {
        global.Car3DBridge.onVehicleTap();
      }
    } catch (e) {
      // ignore in desktop preview
    }
  }

  function notifyAndroidDoubleTap() {
    try {
      if (global.Car3DBridge && typeof global.Car3DBridge.onVehicleDoubleTap === 'function') {
        global.Car3DBridge.onVehicleDoubleTap();
      }
    } catch (e) {
      // ignore in desktop preview
    }
  }

  var pendingSingleTapTimer = null;
  function dispatchVehicleTap() {
    if (pendingSingleTapTimer !== null) {
      clearTimeout(pendingSingleTapTimer);
      pendingSingleTapTimer = null;
      notifyAndroidDoubleTap();
    } else {
      pendingSingleTapTimer = setTimeout(function () {
        pendingSingleTapTimer = null;
        notifyAndroidTap();
      }, 260);
    }
  }

  function notifyAndroidEngineReady() {
    try {
      if (global.Car3DBridge && typeof global.Car3DBridge.onEngineReady === 'function') {
        global.Car3DBridge.onEngineReady();
      }
    } catch (e) {
      // ignore in desktop preview
    }
  }

  function notifyAndroidReady() {
    try {
      if (global.Car3DBridge && typeof global.Car3DBridge.onSceneReady === 'function') {
        global.Car3DBridge.onSceneReady(state.modelKey);
      }
    } catch (e) {
      // ignore in desktop preview
    }
  }

  function notifyAndroidFirstFrame() {
    try {
      if (global.Car3DBridge && typeof global.Car3DBridge.onFirstFrameRendered === 'function') {
        global.Car3DBridge.onFirstFrameRendered();
      }
    } catch (e) {
      // ignore in desktop preview
    }
  }

  function notifyAndroidFirstDrag() {
    if (hasNotifiedFirstDrag) return;
    hasNotifiedFirstDrag = true;
    try {
      if (global.Car3DBridge && typeof global.Car3DBridge.onFirstOrbitDrag === 'function') {
        global.Car3DBridge.onFirstOrbitDrag();
      }
    } catch (e) {
      // ignore in desktop preview
    }
  }

  function clampPhi(p) {
    return clamp(p, 0.14, 1.44);
  }

  function bindPointerControls(domEl) {
    var touchTrackingTap = false;
    domEl.addEventListener('touchstart', function (e) {
      if (e.touches.length === 1) {
        touchTrackingTap = true;
        orbit.startX = orbit.lastX = e.touches[0].clientX;
        orbit.startY = orbit.lastY = e.touches[0].clientY;
        orbit.movedDist = 0;
        if (orbit.topDownLocked) {
          orbit.dragging = false;
          return;
        }
        orbit.dragging = true;
        orbit.velTheta = 0;
        orbit.velPhi = 0;
        orbit.lastMoveTime = performance.now();
      } else {
        touchTrackingTap = false;
        orbit.dragging = false;
      }
    }, { passive: true });

    domEl.addEventListener('touchmove', function (e) {
      if (e.touches.length !== 1) return;
      var cx = e.touches[0].clientX;
      var cy = e.touches[0].clientY;
      var dx = cx - orbit.lastX;
      var dy = cy - orbit.lastY;
      orbit.lastX = cx;
      orbit.lastY = cy;
      orbit.movedDist += Math.abs(dx) + Math.abs(dy);

      if (orbit.topDownLocked || !orbit.dragging) return;
      var now = performance.now();
      var moveDt = Math.max(0.008, Math.min(0.05, (now - orbit.lastMoveTime) / 1000));
      orbit.lastMoveTime = now;

      var dTheta = -dx * 0.0085;
      var dPhi = -dy * 0.0055;
      orbit.targetTheta += dTheta;
      orbit.targetPhi = clampPhi(orbit.targetPhi + dPhi);
      if (orbit.movedDist >= 8) {
        finishEntranceImmediately();
        notifyAndroidFirstDrag();
      }

      orbit.velTheta = lerp(orbit.velTheta, clamp(dTheta / moveDt, -7.5, 7.5), 0.45);
      orbit.velPhi = lerp(orbit.velPhi, clamp(dPhi / moveDt, -3.5, 3.5), 0.45);
    }, { passive: true });

    domEl.addEventListener('touchend', function (e) {
      if (e.touches.length === 0) {
        var idleMs = performance.now() - orbit.lastMoveTime;
        if (touchTrackingTap && orbit.movedDist < 12) {
          orbit.velTheta = 0;
          orbit.velPhi = 0;
          dispatchVehicleTap();
        } else if (orbit.dragging && idleMs > 80) {
          orbit.velTheta = 0;
          orbit.velPhi = 0;
        }
        touchTrackingTap = false;
        orbit.dragging = false;
      }
    }, { passive: true });

    domEl.addEventListener('touchcancel', function () {
      touchTrackingTap = false;
      orbit.dragging = false;
      orbit.velTheta = 0;
      orbit.velPhi = 0;
    }, { passive: true });

    var mouseTrackingTap = false;
    domEl.addEventListener('mousedown', function (e) {
      mouseTrackingTap = true;
      orbit.startX = orbit.lastX = e.clientX;
      orbit.startY = orbit.lastY = e.clientY;
      orbit.movedDist = 0;
      if (orbit.topDownLocked) {
        orbit.dragging = false;
        return;
      }
      orbit.dragging = true;
      orbit.velTheta = 0;
      orbit.velPhi = 0;
      orbit.lastMoveTime = performance.now();
    });
    window.addEventListener('mousemove', function (e) {
      if (!mouseTrackingTap && !orbit.dragging) return;
      var dx = e.clientX - orbit.lastX;
      var dy = e.clientY - orbit.lastY;
      orbit.lastX = e.clientX;
      orbit.lastY = e.clientY;
      orbit.movedDist += Math.abs(dx) + Math.abs(dy);

      if (orbit.topDownLocked || !orbit.dragging) return;
      var now = performance.now();
      var moveDt = Math.max(0.008, Math.min(0.05, (now - orbit.lastMoveTime) / 1000));
      orbit.lastMoveTime = now;

      var dTheta = -dx * 0.008;
      var dPhi = -dy * 0.005;
      orbit.targetTheta += dTheta;
      orbit.targetPhi = clampPhi(orbit.targetPhi + dPhi);
      if (orbit.movedDist >= 8) {
        finishEntranceImmediately();
        notifyAndroidFirstDrag();
      }

      orbit.velTheta = lerp(orbit.velTheta, clamp(dTheta / moveDt, -7.5, 7.5), 0.45);
      orbit.velPhi = lerp(orbit.velPhi, clamp(dPhi / moveDt, -3.5, 3.5), 0.45);
    });
    window.addEventListener('mouseup', function () {
      if (!mouseTrackingTap && !orbit.dragging) return;
      var wasDragging = orbit.dragging;
      var wasTap = mouseTrackingTap && orbit.movedDist < 8;
      mouseTrackingTap = false;
      orbit.dragging = false;
      var idleMs = performance.now() - orbit.lastMoveTime;
      if (wasTap) {
        orbit.velTheta = 0;
        orbit.velPhi = 0;
        dispatchVehicleTap();
      } else if (wasDragging && idleMs > 80) {
        orbit.velTheta = 0;
        orbit.velPhi = 0;
      }
    });
  }

  function computeDynamicRadius(phi, aspect, visRatio, theta) {
    var vr = clamp(typeof visRatio === 'number' ? visRatio : 1.0, 0.40, 1.0);
    var th = (typeof theta === 'number' && isFinite(theta)) ? theta : orbit.theta;
    var vFovRad = (camera ? camera.fov : 34) * Math.PI / 180;
    var halfV = vFovRad / 2;
    var tanHalfV = Math.tan(halfV);
    var effectiveHalfV = Math.atan(tanHalfV * vr);
    var tanHalfH = tanHalfV * Math.max(aspect, 0.6);
    var halfH = Math.atan(tanHalfH);

    var rTop = 2.58 / Math.sin(effectiveHalfV);
    var distH = 2.74 / Math.sin(halfH);

    // Angle-aware horizontal fit so full ~4.8m side profiles (|theta| ~ PI/2) never clip bumpers on narrow phones
    var halfLen = (currentModelSize && currentModelSize.z > 1.0) ? (currentModelSize.z * 0.505) : 2.42;
    var halfWid = (currentModelSize && currentModelSize.x > 0.5) ? (currentModelSize.x * 0.515) : 0.98;
    var absSin = Math.abs(Math.sin(th));
    var absCos = Math.abs(Math.cos(th));
    var projHalfX = halfLen * absSin + halfWid * absCos;
    var sideFactor = smoothstep(0.65, 1.0, absSin);
    var sideDepthMargin = halfWid * absSin + halfLen * absCos * 0.45;
    var distSide = sideDepthMargin + (projHalfX * 1.19) / Math.max(tanHalfH, 0.18);
    var effectiveDistH = lerp(distH, Math.max(distH, distSide), sideFactor);

    var rHero = Math.max(6.4 / Math.max(vr, 0.72), effectiveDistH);

    var topFactor = smoothstep(DEFAULT_PHI, 0.14, phi);
    return lerp(rHero, rTop, topFactor);
  }

  var lastResizeW = 0;
  var lastResizeH = 0;

  function applyCameraViewOffset() {
    if (!camera) return;
    var w = lastResizeW || window.innerWidth || 360;
    var h = lastResizeH || window.innerHeight || 256;
    var visRatio = lerp(collapsedVisibleRatio, expandedVisibleRatio, smoothDeckProgress);
    var yOff = Math.round((1.0 - visRatio) * h * 0.5);
    if (yOff !== lastViewOffsetY || w !== lastViewOffsetW || h !== lastViewOffsetH) {
      lastViewOffsetY = yOff;
      lastViewOffsetW = w;
      lastViewOffsetH = h;
      if (yOff <= 0) {
        camera.clearViewOffset();
      } else {
        camera.setViewOffset(w, h, 0, yOff, w, h);
      }
    }
  }

  function resize() {
    if (!renderer || !camera) return;
    var el = document.getElementById('stage');
    var w = (el && el.clientWidth) || window.innerWidth || 360;
    var h = (el && el.clientHeight) || window.innerHeight || 256;
    if (w <= 0 || h <= 0) return;
    if (w === lastResizeW && h === lastResizeH) return;
    lastResizeW = w;
    lastResizeH = h;
    var aspect = w / Math.max(1, h);
    camera.aspect = aspect;
    lastViewOffsetY = -1;
    applyCameraViewOffset();
    var visRatio = lerp(collapsedVisibleRatio, expandedVisibleRatio, smoothDeckProgress);
    var fitR = computeDynamicRadius(orbit.targetPhi, aspect, visRatio, orbit.targetTheta);
    orbit.targetRadius = fitR;
    if (elapsed < 0.1) {
      orbit.radius = fitR;
    }
    renderer.setSize(w, h, false);
    if (renderActive && scene) {
      renderer.render(scene, camera);
    }
  }

  function animate(now) {
    if (!renderActive) {
      rafId = null;
      return;
    }
    rafId = requestAnimationFrame(animate);

    var dt = lastTime ? Math.min(0.05, (now - lastTime) / 1000) : 0.016;
    lastTime = now;
    elapsed += dt;

    // Physical angular momentum inertia with exponential turntable friction
    if (!orbit.dragging) {
      if (Math.abs(orbit.velTheta) > 0.001 || Math.abs(orbit.velPhi) > 0.001) {
        orbit.targetTheta += orbit.velTheta * dt;
        var nextPhi = clampPhi(orbit.targetPhi + orbit.velPhi * dt);
        if (nextPhi === 0.14 || nextPhi === 1.44) {
          orbit.velPhi = 0;
        }
        orbit.targetPhi = nextPhi;
        var friction = Math.exp(-dt * 4.8);
        orbit.velTheta *= friction;
        orbit.velPhi *= friction;
      } else {
        orbit.velTheta = 0;
        orbit.velPhi = 0;
      }
    }

    var damp = 1.0 - Math.exp(-dt * 9.5);
    smoothDeckProgress += (orbit.deckProgress - smoothDeckProgress) * damp;
    if (Math.abs(orbit.deckProgress - smoothDeckProgress) < 0.0005) {
      smoothDeckProgress = orbit.deckProgress;
    }
    var visRatio = lerp(collapsedVisibleRatio, expandedVisibleRatio, smoothDeckProgress);
    applyCameraViewOffset();

    if (camera) {
      orbit.targetRadius = computeDynamicRadius(orbit.targetPhi, camera.aspect, visRatio, orbit.targetTheta);
    }

    orbit.theta += (orbit.targetTheta - orbit.theta) * damp;
    orbit.phi += (orbit.targetPhi - orbit.phi) * damp;
    orbit.radius += (orbit.targetRadius - orbit.radius) * damp;
    updateCameraPosition();

    // Compute physical suspension targets
    var targetPitch = 0.0;
    var targetRoll = 0.0;
    var targetHeave = 0.0;

    // Physically grounded multi-segment entrance choreography
    if (entrance.active && currentContainer && entrance.segments.length > 0) {
      entrance.elapsed += dt;
      applyEntranceCameraEnvelope(entrance.elapsed);
      if (camera) {
        updateCameraPosition();
      }

      // Update minimalist ground parking bay fade envelope if active for this style
      if (parkingBay) {
        if (entrance.bayPose) {
          var bp = entrance.bayPose;
          var inAlpha = bp.fadeInEnd > 0 ? smoothstep(0, bp.fadeInEnd, entrance.elapsed) : 1.0;
          var outAlpha = 1.0 - smoothstep(bp.fadeOutStart, bp.fadeOutEnd, entrance.elapsed);
          entrance.bayAlpha = inAlpha * outAlpha;
          parkingBay.setAlpha(entrance.bayAlpha);
        } else {
          entrance.bayAlpha = 0;
          parkingBay.setAlpha(0);
        }
      }

      if (entrance.elapsed >= entrance.totalDuration) {
        if (!entrance.arrivalBlinkFired) {
          entrance.arrivalBlinkFired = true;
          if (currentEffects && currentEffects.triggerHazardBlink) {
            currentEffects.triggerHazardBlink(2);
          }
        }
        finishEntranceImmediately();
      } else {
        // Locate active segment and local normalized time
        var accTime = 0;
        var segIdx = entrance.segments.length - 1;
        var segLocalT = 1.0;
        for (var si = 0; si < entrance.segments.length; si++) {
          var sDur = entrance.segments[si].duration;
          if (entrance.elapsed < accTime + sDur || si === entrance.segments.length - 1) {
            segIdx = si;
            segLocalT = clamp((entrance.elapsed - accTime) / Math.max(0.001, sDur), 0, 1);
            break;
          }
          accTime += sDur;
        }

        // Fire stop impulse for any drive segment that just finished on a segment boundary
        if (entrance.prevSegIdx >= 0 && entrance.prevSegIdx < segIdx) {
          for (var doneIdx = entrance.prevSegIdx; doneIdx < segIdx; doneIdx++) {
            var doneSeg = entrance.segments[doneIdx];
            if (doneSeg && doneSeg.type === 'drive' && doneSeg.stopImpulse && !doneSeg.stopFired) {
              doneSeg.stopFired = true;
              impulseSuspension(doneSeg.stopImpulse[0], doneSeg.stopImpulse[1], doneSeg.stopImpulse[2]);
            }
          }
        }
        var segChanged = segIdx !== entrance.prevSegIdx;
        entrance.prevSegIdx = segIdx;

        var seg = entrance.segments[segIdx];
        if (seg.type === 'hold') {
          currentContainer.position.set(seg.x, 0, seg.z);
          currentContainer.rotation.set(0, seg.yaw, 0);
          entrance.prevX = seg.x;
          entrance.prevZ = seg.z;
          entrance.prevSpeed = 0;

          if (seg.wakeImpulse && !seg.wakeImpulseFired && segLocalT >= 0.20) {
            seg.wakeImpulseFired = true;
            impulseSuspension(seg.wakeImpulse[0], seg.wakeImpulse[1], seg.wakeImpulse[2]);
          }

          // Trigger welcome hazard double-flash once the car settles cleanly at its final showcase stop
          if (segIdx === entrance.segments.length - 1 && segLocalT >= 0.45 && !entrance.arrivalBlinkFired) {
            entrance.arrivalBlinkFired = true;
            if (currentEffects && currentEffects.triggerHazardBlink) {
              currentEffects.triggerHazardBlink(2);
            }
          }

          var targetSteerHold = typeof seg.steerTarget === 'number' ? seg.steerTarget : 0;
          entrance.steerAngle = lerp(entrance.steerAngle, targetSteerHold, 1.0 - Math.exp(-dt * 11.0));
          if (currentRig) {
            currentRig.setSteering(entrance.steerAngle);
          }

          entrance.headGlow = lerp(seg.headStart || 0, seg.headEnd || 0, smoothstep(0, 1, segLocalT));
          entrance.brakeGlow = lerp(seg.brakeStart || 0, seg.brakeEnd || 0, smoothstep(0, 1, segLocalT));

          if (typeof seg.reverseSwitchT === 'number') {
            var revP = smoothstep(seg.reverseSwitchT, 1.0, segLocalT);
            entrance.reverseGlow = lerp(seg.reverseStart || 0, seg.reverseEnd || 0, revP);
          } else if (typeof seg.reverseReleaseT === 'number') {
            var relP = smoothstep(0.0, seg.reverseReleaseT, segLocalT);
            entrance.reverseGlow = lerp(seg.reverseStart || 0, seg.reverseEnd || 0, relP);
          } else {
            entrance.reverseGlow = lerp(seg.reverseStart || 0, seg.reverseEnd || 0, segLocalT);
          }
        } else {
          var u = evalEaseProfile(seg.ease, segLocalT);
          var pos = evalCubicBezier(seg.p0, seg.p1, seg.p2, seg.p3, u);
          var d1 = evalCubicBezierDeriv(seg.p0, seg.p1, seg.p2, seg.p3, u);
          var d2 = evalCubicBezierSecondDeriv(seg.p0, seg.p1, seg.p2, seg.p3, u);

          if (segChanged) {
            entrance.prevX = seg.p0.x;
            entrance.prevZ = seg.p0.z;
            entrance.prevSpeed = 0;
          }

          var dx = pos.x - entrance.prevX;
          var dz = pos.z - entrance.prevZ;
          var ds = Math.sqrt(dx * dx + dz * dz);
          var speed = dt > 0.0001 ? ds / dt : 0;
          var accel = dt > 0.0001 ? (speed - entrance.prevSpeed) / dt : 0;
          entrance.prevX = pos.x;
          entrance.prevZ = pos.z;
          entrance.prevSpeed = speed;

          var dir = seg.dir < 0 ? -1 : 1;
          var yaw = dir < 0 ? Math.atan2(-d1.x, -d1.z) : Math.atan2(d1.x, d1.z);
          currentContainer.position.set(pos.x, 0, pos.z);
          currentContainer.rotation.set(0, yaw, 0);

          // Exact ground-arc wheel rolling (+dir rolls forward, -dir rolls cleanly backward)
          var wheelR = currentRig ? currentRig.wheelRadius : DEFAULT_WHEEL_R;
          entrance.wheelSpin += dir * (ds / Math.max(0.18, wheelR));
          if (currentRig) {
            currentRig.setWheelRoll(entrance.wheelSpin);
          }

          // Signed path curvature kappa = d(yaw)/ds; Ackermann front steering = atan(dir * L * kappa)
          var speedParamSq = d1.x * d1.x + d1.z * d1.z;
          var kappa = speedParamSq > 0.0001
            ? (d1.z * d2.x - d1.x * d2.z) / Math.pow(speedParamSq, 1.5)
            : 0;

          var fadeStart = typeof seg.straightFadeStart === 'number' ? seg.straightFadeStart : 0.76;
          var straightFade = fadeStart < 0.95 ? (1.0 - smoothstep(fadeStart, 0.99, segLocalT)) : 1.0;
          var desiredSteer = Math.atan(dir * WHEELBASE_M * kappa) * straightFade;
          entrance.steerAngle = lerp(entrance.steerAngle, desiredSteer, 1.0 - Math.exp(-dt * 12.0));
          if (currentRig) {
            currentRig.setSteering(entrance.steerAngle);
          }

          // Physically accurate longitudinal pitch (dive/squat) & lateral roll for both forward & reverse
          var longAccel = dir * accel;
          var latAccel = dir * speed * speed * kappa;
          targetPitch = clamp(-longAccel * 0.0034, -0.022, 0.024);
          targetRoll = clamp(-latAccel * 0.0042, -0.022, 0.022);
          targetHeave = -Math.abs(targetPitch) * 0.40;

          if (segLocalT >= 0.97 && seg.stopImpulse && !seg.stopFired) {
            seg.stopFired = true;
            impulseSuspension(seg.stopImpulse[0], seg.stopImpulse[1], seg.stopImpulse[2]);
          }

          // Smooth DRL, brake, and reverse light envelopes
          entrance.headGlow = lerp(seg.headStart || 0.6, seg.headEnd || 0.35, segLocalT);
          var brStart = typeof seg.brakeStartT === 'number' ? seg.brakeStartT : 0.45;
          var brPeak = typeof seg.brakePeakT === 'number' ? seg.brakePeakT : 0.85;
          var targetBrake = smoothstep(brStart, brPeak, segLocalT) * 0.88;
          entrance.brakeGlow = lerp(entrance.brakeGlow, targetBrake, 1.0 - Math.exp(-dt * 14.0));
          var targetRev = seg.reverseActive ? 1.0 : 0.0;
          entrance.reverseGlow = lerp(entrance.reverseGlow, targetRev, 1.0 - Math.exp(-dt * 16.0));
        }
      }
    }

    // Subtle physical engine-running chassis idle micro-vibration
    var engineVibY = 0;
    var engineVibRoll = 0;
    if (state.engineRunning && !entrance.active) {
      engineVibY = Math.sin(elapsed * 46.0) * 0.0012 + Math.sin(elapsed * 29.0) * 0.0007;
      engineVibRoll = Math.sin(elapsed * 34.0) * 0.0016;
    }

    // Integrate damped spring suspension and apply strictly to sprung chassisGroup (wheels stay grounded!)
    stepSuspension(dt, targetPitch, targetRoll, targetHeave);
    if (currentChassis) {
      currentChassis.rotation.x = suspension.pitch;
      currentChassis.rotation.z = suspension.roll + engineVibRoll;
      currentChassis.position.y = suspension.heave + engineVibY;
    }

    if (currentEffects && typeof currentEffects.update === 'function') {
      currentEffects.update(dt, elapsed, state);
    }

    renderer.render(scene, camera);
  }

  function init() {
    var container = document.getElementById('stage');
    try {
      renderer = new THREE.WebGLRenderer({
        antialias: true,
        alpha: false,
        powerPreference: 'high-performance'
      });
    } catch (err) {
      if (global.Car3DBridge && typeof global.Car3DBridge.onWebGLError === 'function') {
        global.Car3DBridge.onWebGLError(String(err));
      }
      return;
    }

    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2.2));
    renderer.setClearColor(BG_HEX, 1);
    renderer.outputEncoding = THREE.sRGBEncoding;
    renderer.toneMapping = THREE.ACESFilmicToneMapping;
    renderer.toneMappingExposure = 1.25;
    container.appendChild(renderer.domElement);

    scene = new THREE.Scene();
    scene.background = new THREE.Color(BG_HEX);

    camera = new THREE.PerspectiveCamera(34, (window.innerWidth || 360) / Math.max(1, window.innerHeight || 256), 0.1, 60);

    envMap = createStudioEnvironment(renderer);
    scene.environment = envMap;

    scene.add(new THREE.HemisphereLight(0xf2f6ff, 0x262c38, 1.05));

    var keyLight = new THREE.DirectionalLight(0xffffff, 1.55);
    keyLight.position.set(5.0, 7.0, 5.5);
    scene.add(keyLight);

    var rimLight = new THREE.DirectionalLight(0xcde0ff, 1.15);
    rimLight.position.set(-5.5, 4.2, -5.5);
    scene.add(rimLight);

    var frontFill = new THREE.DirectionalLight(0xe6f0ff, 0.75);
    frontFill.position.set(-2.5, 2.0, 6.5);
    scene.add(frontFill);

    gltfLoader = new THREE.GLTFLoader();

    try {
      var params = new URLSearchParams(window.location.search || '');
      var demo = params.get('demo');
      if (demo === 'rear') {
        hasCustomDemoAngle = true;
        orbit.theta = orbit.targetTheta = -2.36;
        orbit.phi = orbit.targetPhi = 1.24;
      } else if (demo === 'open') {
        hasCustomDemoAngle = true;
        state.vehicleLocked = false;
        state.doorsOpen = true;
        state.trunkOpen = true;
        state.skylightOpen = true;
        state.skylightMode = 'open';
        state.windowsMode = 'vent';
        state.climateOn = true;
        state.engineRunning = true;
        orbit.theta = orbit.targetTheta = 0.84;
        orbit.phi = orbit.targetPhi = 1.20;
      }
    } catch (e) {
      // ignore
    }

    updateCameraPosition();
    resize();
    bindPointerControls(renderer.domElement);
    window.addEventListener('resize', resize);
    if (typeof ResizeObserver !== 'undefined' && container) {
      var ro = new ResizeObserver(function () {
        resize();
      });
      ro.observe(container);
    }

    lastTime = performance.now();
    animate(lastTime);

    // Inside Android, wait for VehicleModelRepository to supply the exact local cached GLB URL;
    // in standalone desktop browser preview, auto-load the default registry URL.
    if (global.Car3DBridge && typeof global.Car3DBridge.onEngineReady === 'function') {
      notifyAndroidEngineReady();
    } else {
      loadModel(state.modelKey);
    }
  }

  global.Car3D = {
    loadModel: function (modelKey, customGlbUrl) {
      loadModel(modelKey, customGlbUrl);
    },
    setState: function (patch) {
      if (!patch || typeof patch !== 'object') return;
      if (typeof patch.modelKey === 'string' && patch.modelKey && typeof patch.glbUrl === 'string' && patch.glbUrl) {
        loadModel(patch.modelKey, patch.glbUrl);
      }
      if (typeof patch.vehicleLocked === 'boolean') state.vehicleLocked = patch.vehicleLocked;
      if (typeof patch.doorsOpen === 'boolean') state.doorsOpen = patch.doorsOpen;
      if (typeof patch.trunkOpen === 'boolean') state.trunkOpen = patch.trunkOpen;
      if (typeof patch.skylightOpen === 'boolean') {
        state.skylightOpen = patch.skylightOpen;
        if (typeof patch.skylightMode !== 'string') {
          state.skylightMode = patch.skylightOpen ? 'open' : 'closed';
        }
      }
      if (typeof patch.skylightMode === 'string' && patch.skylightMode) {
        state.skylightMode = patch.skylightMode;
      }
      if (typeof patch.windowsMode === 'string' && patch.windowsMode) {
        state.windowsMode = patch.windowsMode;
      }
      if (typeof patch.climateOn === 'boolean') state.climateOn = patch.climateOn;
      if (typeof patch.climateTemp === 'number' && isFinite(patch.climateTemp)) {
        state.climateTemp = patch.climateTemp;
      }
      if (typeof patch.engineRunning === 'boolean') state.engineRunning = patch.engineRunning;
    },
    flashLights: function () {
      if (currentEffects && currentEffects.triggerFlash) {
        currentEffects.triggerFlash();
      }
    },
    triggerHazardBlink: function (pulses) {
      if (currentEffects && currentEffects.triggerHazardBlink) {
        currentEffects.triggerHazardBlink(pulses);
      }
    },
    triggerActionFeedback: function (actionKey) {
      if (currentEffects && currentEffects.triggerFeedback) {
        currentEffects.triggerFeedback(String(actionKey || ''));
      }
    },
    resetCamera: function () {
      finishEntranceImmediately();
      orbit.deckProgress = 0;
      orbit.topDownLocked = false;
      orbit.velTheta = 0;
      orbit.velPhi = 0;
      orbit.targetTheta = activeRestTheta;
      orbit.targetPhi = activeRestPhi;
    },
    setCollapsedVisibleRatio: function (ratio) {
      if (typeof ratio === 'number' && isFinite(ratio)) {
        collapsedVisibleRatio = clamp(ratio, 0.40, 1.0);
        lastViewOffsetY = -1;
        applyCameraViewOffset();
        if (camera) {
          var vr = lerp(collapsedVisibleRatio, expandedVisibleRatio, smoothDeckProgress);
          var fitR = computeDynamicRadius(orbit.targetPhi, camera.aspect, vr, orbit.targetTheta);
          orbit.targetRadius = fitR;
          if (elapsed < 0.25) {
            orbit.radius = fitR;
          }
          updateCameraPosition();
        }
      }
    },
    setExpandedVisibleRatio: function (ratio) {
      if (typeof ratio === 'number' && isFinite(ratio)) {
        expandedVisibleRatio = clamp(ratio, 0.40, 1.0);
        lastViewOffsetY = -1;
        applyCameraViewOffset();
        if (camera) {
          var vr = lerp(collapsedVisibleRatio, expandedVisibleRatio, smoothDeckProgress);
          var fitR = computeDynamicRadius(orbit.phi, camera.aspect, vr, orbit.theta);
          orbit.targetRadius = fitR;
          orbit.radius = fitR;
          updateCameraPosition();
          if (renderActive && renderer && scene) {
            renderer.render(scene, camera);
          }
        }
      }
    },
    setDeckProgress: function (progress) {
      if (progress > 0.01) finishEntranceImmediately();
      var p = clamp(typeof progress === 'number' ? progress : 0, 0, 1);
      var prev = orbit.deckProgress;
      var twoPi = Math.PI * 2;

      if (prev <= 0.001 && p > 0.001) {
        orbit.deckStartTheta = orbit.theta;
        orbit.deckStartPhi = orbit.phi;
        orbit.deckEndTheta = orbit.theta + wrapAngleDelta(orbit.theta, Math.PI);
      }

      orbit.deckProgress = p;
      smoothDeckProgress = p;
      orbit.velTheta = 0;
      orbit.velPhi = 0;

      if (p > 0.001) {
        orbit.topDownLocked = true;
        orbit.dragging = false;
        var nextTheta = lerp(orbit.deckStartTheta, orbit.deckEndTheta, p);
        var nextPhi = lerp(orbit.deckStartPhi, 0.05, p);
        orbit.theta = nextTheta;
        orbit.targetTheta = nextTheta;
        orbit.phi = nextPhi;
        orbit.targetPhi = nextPhi;
      } else {
        orbit.topDownLocked = false;
        if (prev > 0.001) {
          orbit.theta = orbit.deckStartTheta;
          orbit.targetTheta = orbit.deckStartTheta;
          orbit.phi = orbit.deckStartPhi;
          orbit.targetPhi = orbit.deckStartPhi;
        }
      }

      applyCameraViewOffset();
      if (camera) {
        var vr = lerp(collapsedVisibleRatio, expandedVisibleRatio, smoothDeckProgress);
        var exactR = computeDynamicRadius(orbit.phi, camera.aspect, vr, orbit.theta);
        orbit.radius = exactR;
        orbit.targetRadius = exactR;
      }
      updateCameraPosition();
      if (renderActive && renderer && scene && camera) {
        renderer.render(scene, camera);
      }
    },
    setTopDownFocus: function (enabled) {
      global.Car3D.setDeckProgress(enabled ? 1 : 0);
    },
    setCameraAngle: function (theta, phi) {
      orbit.velTheta = 0;
      orbit.velPhi = 0;
      if (typeof theta === 'number') orbit.targetTheta = theta;
      if (typeof phi === 'number') orbit.targetPhi = clampPhi(phi);
    },
    setRenderActive: function (active) {
      renderActive = !!active;
      if (renderActive && rafId === null && renderer) {
        lastTime = performance.now();
        animate(lastTime);
      }
    },
    setEntranceStyle: function (style) {
      preferredEntranceStyle = normalizeEntranceStyle(style);
    },
    playEntrance: function (styleOverride) {
      if (!hasCustomDemoAngle) {
        startEntranceAnimation(styleOverride);
      }
    },
    playEntranceAnimation: function (styleOverride) {
      global.Car3D.playEntrance(styleOverride);
    },
    resize: resize
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})(window);
