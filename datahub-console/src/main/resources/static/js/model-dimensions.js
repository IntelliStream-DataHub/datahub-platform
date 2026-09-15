/*
 * Dimension lines over the 3D viewer: a box's length, width and height drawn beside it, CAD style,
 * on a 2D canvas above the model and redrawn whenever the viewer renders.
 */
window.ModelDimensions = (function () {
	"use strict";

	var ARROW = 8;

	/** Overlays `container`, the viewer canvas's parent; returns show(box, metres, units), clear(). */
	function attach(three, container) {
		var canvas = document.createElement("canvas");
		canvas.className = "dh-model-lines";
		container.appendChild(canvas);
		var shown = null;

		// The viewer renders on every orbit, pan, zoom and resize, so draw straight after it.
		var render = three.Render;
		three.Render = function () {
			render.apply(three, arguments);
			draw();
		};

		function draw() {
			var width = three.canvas.clientWidth;
			var height = three.canvas.clientHeight;
			var ratio = window.devicePixelRatio || 1;
			if (canvas.width !== Math.round(width * ratio) || canvas.height !== Math.round(height * ratio)) {
				canvas.width = Math.round(width * ratio);
				canvas.height = Math.round(height * ratio);
			}
			var ctx = canvas.getContext("2d");
			ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
			ctx.clearRect(0, 0, width, height);
			if (!shown) {
				return;
			}
			var camera = three.camera;
			var view = camera.matrixWorldInverse.elements;
			var projection = camera.projectionMatrix.elements;
			// World to canvas pixels, or null behind the camera. Both matrices are column-major.
			function project(p) {
				var v = transform(view, [p[0], p[1], p[2], 1]);
				var clip = transform(projection, v);
				var w = clip[3];
				return w > 1e-9 ? [(clip[0] / w + 1) / 2 * width, (1 - clip[1] / w) / 2 * height] : null;
			}

			var style = getComputedStyle(container);
			var colour = style.getPropertyValue("--accent").trim() || "#6cb1ff";
			ctx.font = "600 12px " + style.fontFamily;
			ctx.textAlign = "center";
			ctx.textBaseline = "middle";
			var eye = [camera.position.x, camera.position.y, camera.position.z];
			var middle = project(shown.box.centre);

			ModelMeasure.dimensionLines(shown.box, eye).forEach(function (line) {
				var a = project(line.from);
				var b = project(line.to);
				if (!a || !b) {
					return;
				}
				ctx.strokeStyle = colour;
				ctx.fillStyle = colour;
				ctx.lineWidth = 1;
				ctx.globalAlpha = 0.7;
				line.extensions.forEach(function (ext) {
					var p = project(ext[0]);
					var q = project(ext[1]);
					if (p && q) {
						segment(ctx, p, q);
					}
				});
				ctx.globalAlpha = 1;
				ctx.lineWidth = 1.5;
				segment(ctx, a, b);
				var length = Math.hypot(b[0] - a[0], b[1] - a[1]);
				if (length > 3 * ARROW) {
					arrow(ctx, a, b, length);
					arrow(ctx, b, a, length);
				}
				if (length > 4) {
					label(ctx, style, a, b, length, middle,
						shown.units.value(line.extent * shown.metres) + " " + shown.units.unit);
				}
			});
		}

		return {
			show: function (box, metres, units) {
				shown = { box: box, metres: metres, units: units };
				draw();
			},
			clear: function () {
				shown = null;
				draw();
			}
		};
	}

	function transform(m, v) {
		return [0, 1, 2, 3].map(function (r) {
			return m[r] * v[0] + m[4 + r] * v[1] + m[8 + r] * v[2] + m[12 + r] * v[3];
		});
	}

	function segment(ctx, a, b) {
		ctx.beginPath();
		ctx.moveTo(a[0], a[1]);
		ctx.lineTo(b[0], b[1]);
		ctx.stroke();
	}

	/** A filled arrowhead at `tip`, pointing away from `from`. */
	function arrow(ctx, tip, from, length) {
		var ux = (tip[0] - from[0]) / length;
		var uy = (tip[1] - from[1]) / length;
		ctx.beginPath();
		ctx.moveTo(tip[0], tip[1]);
		ctx.lineTo(tip[0] - ux * ARROW - uy * ARROW * 0.4, tip[1] - uy * ARROW + ux * ARROW * 0.4);
		ctx.lineTo(tip[0] - ux * ARROW + uy * ARROW * 0.4, tip[1] - uy * ARROW - ux * ARROW * 0.4);
		ctx.closePath();
		ctx.fill();
	}

	/**
	 * The value on a pill at the line's midpoint, breaking the line as a drawn dimension does, or
	 * beside it, away from the model, when the line is too short to hold it.
	 */
	function label(ctx, style, a, b, length, middle, text) {
		var w = ctx.measureText(text).width + 12;
		var h = 20;
		var at = [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2];
		if (length < w + 3 * ARROW) {
			var nx = -(b[1] - a[1]) / length;
			var ny = (b[0] - a[0]) / length;
			if (middle && nx * (at[0] - middle[0]) + ny * (at[1] - middle[1]) < 0) {
				nx = -nx;
				ny = -ny;
			}
			var away = (Math.abs(nx) * w + Math.abs(ny) * h) / 2 + 6;
			at = [at[0] + nx * away, at[1] + ny * away];
		}
		ctx.fillStyle = style.getPropertyValue("--menu-bg").trim() || "#10202c";
		ctx.strokeStyle = style.getPropertyValue("--border").trim() || "#3a4a58";
		ctx.lineWidth = 1;
		ctx.beginPath();
		ctx.roundRect(at[0] - w / 2, at[1] - h / 2, w, h, 6);
		ctx.fill();
		ctx.stroke();
		ctx.fillStyle = style.getPropertyValue("--text").trim() || "#e6edf3";
		ctx.fillText(text, at[0], at[1] + 0.5);
	}

	return { attach: attach };
})();
