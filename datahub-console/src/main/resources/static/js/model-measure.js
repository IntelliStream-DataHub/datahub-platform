/*
 * Sizes for the 3D model viewer: a model's unit, its box along the viewer's axes, and the tightest
 * box around one part. No DOM; called only once the viewer library has loaded.
 */
window.ModelMeasure = (function () {
	"use strict";

	var WORLD_AXES = [[1, 0, 0], [0, 1, 0], [0, 0, 1]];

	// Orientations are searched on at most this many vertices; the box chosen is measured on all.
	var SAMPLE_LIMIT = 50000;

	// Units the library leaves unrecorded but the import settles: glTF and VRML are metres by
	// specification, the COLLADA loader scales to metres and the AMF loader to millimetres, and the
	// CAD decoder writes millimetres for every format it reads.
	var METRE_FORMATS = /\.(dae|glb|gltf|wrl)$/i;
	var MILLIMETRE_FORMATS = /\.(amf|brep|brp|fcstd)$/i;

	// 3MF names its unit and defaults to millimetres, but the loader ignores it, so it is read here.
	var THREE_MF_UNITS = {
		micron: 0.000001, millimeter: 0.001, centimeter: 0.01, inch: 0.0254, foot: 0.3048, meter: 1
	};

	/** Metres per model unit, and whether the unit is known rather than assumed to be metres. */
	function unitScale(model, name, declared) {
		if (declared) {
			return { metres: declared, known: true };
		}
		var metres = {};
		metres[OV.Unit.Millimeter] = 0.001;
		metres[OV.Unit.Centimeter] = 0.01;
		metres[OV.Unit.Meter] = 1;
		metres[OV.Unit.Inch] = 0.0254;
		metres[OV.Unit.Foot] = 0.3048;
		if (metres[model.GetUnit()]) {
			return { metres: metres[model.GetUnit()], known: true };
		}
		if (MILLIMETRE_FORMATS.test(name)) {
			return { metres: 0.001, known: true };
		}
		return { metres: 1, known: METRE_FORMATS.test(name) };
	}

	/** Metres per unit a 3MF declares, read from its root model part, or null for other formats. */
	function declaredUnit(name, blob) {
		if (!/\.3mf$/i.test(name) || typeof DecompressionStream === "undefined") {
			return Promise.resolve(null);
		}
		return zipEntry(blob, /^3D\/[^/]*\.model$/i).then(function (text) {
			if (text === null) {
				return null;
			}
			var tag = /<model\b[^>]*>/i.exec(text);
			if (!tag) {
				return null;
			}
			var unit = /\bunit\s*=\s*["']([a-z]+)["']/i.exec(tag[0]);
			return THREE_MF_UNITS[unit ? unit[1].toLowerCase() : "millimeter"] || null;
		}).catch(function () {
			return null;
		});
	}

	/** The start of the first matching zip entry as text, or null; never inflates it whole. */
	function zipEntry(blob, pattern) {
		var tailSize = Math.min(blob.size, 22 + 65535);
		return blob.slice(blob.size - tailSize).arrayBuffer().then(function (tail) {
			var view = new DataView(tail);
			for (var i = tail.byteLength - 22; i >= 0; i--) {
				if (view.getUint32(i, true) === 0x06054b50) {
					var size = view.getUint32(i + 12, true);
					var offset = view.getUint32(i + 16, true);
					return blob.slice(offset, offset + size).arrayBuffer();
				}
			}
			return null;
		}).then(function (directory) {
			if (!directory) {
				return null;
			}
			var view = new DataView(directory);
			var names = new TextDecoder();
			for (var at = 0; at + 46 <= directory.byteLength && view.getUint32(at, true) === 0x02014b50;) {
				var nameLength = view.getUint16(at + 28, true);
				var name = names.decode(new Uint8Array(directory, at + 46, nameLength));
				if (pattern.test(name)) {
					return entryText(blob, view.getUint16(at + 10, true), view.getUint32(at + 20, true),
						view.getUint32(at + 42, true));
				}
				at += 46 + nameLength + view.getUint16(at + 30, true) + view.getUint16(at + 32, true);
			}
			return null;
		});
	}

	function entryText(blob, method, compressedSize, headerOffset) {
		if ((method !== 0 && method !== 8) || compressedSize === 0xffffffff) {
			return Promise.resolve(null);
		}
		return blob.slice(headerOffset, headerOffset + 30).arrayBuffer().then(function (header) {
			var view = new DataView(header);
			var start = headerOffset + 30 + view.getUint16(26, true) + view.getUint16(28, true);
			var stream = blob.slice(start, start + compressedSize).stream();
			if (method === 8) {
				stream = stream.pipeThrough(new DecompressionStream("deflate-raw"));
			}
			var reader = stream.pipeThrough(new TextDecoderStream()).getReader();
			var text = "";
			return (function read() {
				return reader.read().then(function (chunk) {
					if (!chunk.done) {
						text += chunk.value;
					}
					if (chunk.done || /<model\b[^>]*>/i.test(text) || text.length > 1048576) {
						reader.cancel();
						return text;
					}
					return read();
				});
			})();
		});
	}

	/** The whole model's box along the viewer's axes, from the library's Box3. */
	function modelBox(box3) {
		return {
			axes: WORLD_AXES,
			extents: [box3.max.x - box3.min.x, box3.max.y - box3.min.y, box3.max.z - box3.min.z],
			centre: [(box3.min.x + box3.max.x) / 2, (box3.min.y + box3.max.y) / 2,
				(box3.min.z + box3.max.z) / 2]
		};
	}

	/**
	 * A part's tightest box, smallest of: the viewer's axes, its own frame, its principal axes, and a
	 * minimal cross-section turned about the vertical, the longest principal axis or a main face.
	 */
	function partBox(mesh) {
		var points = worldPoints(mesh);
		if (points.length === 0) {
			return null;
		}
		var sample = everyNth(points, Math.ceil(points.length / 3 / SAMPLE_LIMIT));
		var principal = principalAxes(sample);
		var candidates = [WORLD_AXES, frameAxes(mesh), principal];
		[[0, 1, 0], principal[0]].concat(faceDirections(mesh, 6)).forEach(function (axis) {
			candidates.push(caliperAxes(sample, axis));
		});
		var best = null;
		candidates.forEach(function (axes) {
			var box = boxAlong(points, axes);
			// Summed sides rather than volume, so flat and straight parts still compare. Earlier
			// candidates win within 1%, so a faceted pipe along an axis reads its nominal diameter.
			box.size = box.extents[0] + box.extents[1] + box.extents[2];
			if (!best || box.size < best.size * 0.99) {
				best = box;
			}
		});
		return best;
	}

	/** A mesh's vertices in world space, as flat xyz. */
	function worldPoints(mesh) {
		var p = mesh.geometry.attributes.position.array;
		var e = mesh.matrixWorld.elements;
		var out = new Float64Array(p.length - p.length % 3);
		for (var i = 0; i < out.length; i += 3) {
			out[i] = e[0] * p[i] + e[4] * p[i + 1] + e[8] * p[i + 2] + e[12];
			out[i + 1] = e[1] * p[i] + e[5] * p[i + 1] + e[9] * p[i + 2] + e[13];
			out[i + 2] = e[2] * p[i] + e[6] * p[i + 1] + e[10] * p[i + 2] + e[14];
		}
		return out;
	}

	function everyNth(points, n) {
		if (n <= 1) {
			return points;
		}
		var out = new Float64Array(Math.floor(points.length / 3 / n) * 3);
		for (var i = 0, j = 0; j < out.length; i += 3 * n, j += 3) {
			out[j] = points[i];
			out[j + 1] = points[i + 1];
			out[j + 2] = points[i + 2];
		}
		return out;
	}

	/** The `count` most common face directions in world space, sign-folded. */
	function faceDirections(mesh, count) {
		var normals = mesh.geometry.attributes.normal;
		if (!normals) {
			return [];
		}
		var n = normals.array;
		var e = mesh.matrixWorld.elements;
		var step = 3 * Math.max(1, Math.ceil(n.length / 3 / SAMPLE_LIMIT));
		var buckets = {};
		for (var i = 0; i + 2 < n.length; i += step) {
			var d = normalize([
				e[0] * n[i] + e[4] * n[i + 1] + e[8] * n[i + 2],
				e[1] * n[i] + e[5] * n[i + 1] + e[9] * n[i + 2],
				e[2] * n[i] + e[6] * n[i + 1] + e[10] * n[i + 2]]);
			if (!d) {
				continue;
			}
			var lead = Math.abs(d[0]) > 1e-6 ? d[0] : Math.abs(d[1]) > 1e-6 ? d[1] : d[2];
			if (lead < 0) {
				d = [-d[0], -d[1], -d[2]];
			}
			var key = Math.round(d[0] * 50) + "," + Math.round(d[1] * 50) + "," + Math.round(d[2] * 50);
			if (buckets[key]) {
				buckets[key].count++;
			} else {
				buckets[key] = { count: 1, direction: d };
			}
		}
		return Object.keys(buckets).map(function (k) { return buckets[k]; })
			.sort(function (a, b) { return b.count - a.count; })
			.slice(0, count)
			.map(function (bucket) { return bucket.direction; });
	}

	function boxAlong(points, axes) {
		var centre = [0, 0, 0];
		var extents = axes.map(function (a) {
			var min = Infinity;
			var max = -Infinity;
			for (var i = 0; i < points.length; i += 3) {
				var d = points[i] * a[0] + points[i + 1] * a[1] + points[i + 2] * a[2];
				min = Math.min(min, d);
				max = Math.max(max, d);
			}
			for (var k = 0; k < 3; k++) {
				centre[k] += (min + max) / 2 * a[k];
			}
			return max - min;
		});
		return { axes: axes, extents: extents, centre: centre };
	}

	/** The mesh's own frame from its world matrix, made orthonormal. */
	function frameAxes(mesh) {
		var e = mesh.matrixWorld.elements;
		var x = normalize([e[0], e[1], e[2]]);
		if (!x) {
			return WORLD_AXES;
		}
		var along = dot(x, [e[4], e[5], e[6]]);
		var y = normalize([e[4] - along * x[0], e[5] - along * x[1], e[6] - along * x[2]]);
		return y ? [x, y, cross(x, y)] : WORLD_AXES;
	}

	/** Eigenvectors of the vertex covariance by Jacobi rotations, largest variance first. */
	function principalAxes(points) {
		var n = points.length / 3;
		var mean = [0, 0, 0];
		for (var i = 0; i < points.length; i++) {
			mean[i % 3] += points[i] / n;
		}
		var c = [[0, 0, 0], [0, 0, 0], [0, 0, 0]];
		for (i = 0; i < points.length; i += 3) {
			var d = [points[i] - mean[0], points[i + 1] - mean[1], points[i + 2] - mean[2]];
			for (var r = 0; r < 3; r++) {
				for (var s = 0; s < 3; s++) {
					c[r][s] += d[r] * d[s];
				}
			}
		}
		var v = [[1, 0, 0], [0, 1, 0], [0, 0, 1]];
		for (var sweep = 0; sweep < 50; sweep++) {
			var p = 0;
			var q = 1;
			if (Math.abs(c[0][2]) > Math.abs(c[p][q])) { p = 0; q = 2; }
			if (Math.abs(c[1][2]) > Math.abs(c[p][q])) { p = 1; q = 2; }
			var scale = Math.abs(c[0][0]) + Math.abs(c[1][1]) + Math.abs(c[2][2]);
			if (Math.abs(c[p][q]) <= 1e-12 * scale) {
				break;
			}
			var theta = (c[q][q] - c[p][p]) / (2 * c[p][q]);
			var t = (theta >= 0 ? 1 : -1) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
			var cos = 1 / Math.sqrt(t * t + 1);
			var sin = t * cos;
			rotate(c, p, q, cos, sin, true);
			rotate(c, p, q, cos, sin, false);
			rotate(v, p, q, cos, sin, true);
		}
		return [0, 1, 2].sort(function (a, b) { return c[b][b] - c[a][a]; }).map(function (j) {
			return [v[0][j], v[1][j], v[2][j]];
		});
	}

	/** Applies a Jacobi rotation in the p-q plane to a matrix's columns, or to its rows. */
	function rotate(m, p, q, cos, sin, columns) {
		for (var k = 0; k < 3; k++) {
			var a = columns ? m[k][p] : m[p][k];
			var b = columns ? m[k][q] : m[q][k];
			if (columns) {
				m[k][p] = cos * a - sin * b;
				m[k][q] = sin * a + cos * b;
			} else {
				m[p][k] = cos * a - sin * b;
				m[q][k] = sin * a + cos * b;
			}
		}
	}

	/** Axes turned about `axis` to the smallest cross-section, by rotating calipers on its hull. */
	function caliperAxes(points, axis) {
		var a = normalize(axis);
		var u0 = normalize(Math.abs(a[0]) < 0.9 ? cross(a, [1, 0, 0]) : cross(a, [0, 1, 0]));
		var v0 = cross(a, u0);
		var flat = [];
		for (var i = 0; i < points.length; i += 3) {
			var pt = [points[i], points[i + 1], points[i + 2]];
			flat.push([dot(pt, u0), dot(pt, v0)]);
		}
		var hull = convexHull(flat);
		var best = Infinity;
		var angle = 0;
		for (var h = 0; h < hull.length; h++) {
			var from = hull[h];
			var to = hull[(h + 1) % hull.length];
			var length = Math.hypot(to[0] - from[0], to[1] - from[1]);
			if (length === 0) {
				continue;
			}
			var cu = (to[0] - from[0]) / length;
			var cv = (to[1] - from[1]) / length;
			var along = [Infinity, -Infinity];
			var across = [Infinity, -Infinity];
			hull.forEach(function (q) {
				var x = q[0] * cu + q[1] * cv;
				var y = q[1] * cu - q[0] * cv;
				along = [Math.min(along[0], x), Math.max(along[1], x)];
				across = [Math.min(across[0], y), Math.max(across[1], y)];
			});
			var area = (along[1] - along[0]) * (across[1] - across[0]);
			if (area < best) {
				best = area;
				angle = Math.atan2(cv, cu);
			}
		}
		var cos = Math.cos(angle);
		var sin = Math.sin(angle);
		var u = [0, 1, 2].map(function (k) { return cos * u0[k] + sin * v0[k]; });
		var w = [0, 1, 2].map(function (k) { return cos * v0[k] - sin * u0[k]; });
		return [u, a, w];
	}

	/** Andrew's monotone chain, counter-clockwise, without collinear points. */
	function convexHull(points) {
		points.sort(function (a, b) { return a[0] - b[0] || a[1] - b[1]; });
		function turn(o, a, b) {
			return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
		}
		function half(list) {
			var chain = [];
			list.forEach(function (pt) {
				while (chain.length >= 2 && turn(chain[chain.length - 2], chain[chain.length - 1], pt) <= 0) {
					chain.pop();
				}
				chain.push(pt);
			});
			chain.pop();
			return chain;
		}
		return half(points).concat(half(points.slice().reverse()));
	}

	function dot(a, b) {
		return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
	}

	function cross(a, b) {
		return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
	}

	function normalize(v) {
		var length = Math.sqrt(dot(v, v));
		return length > 0 ? [v[0] / length, v[1] / length, v[2] / length] : null;
	}

	/** Which box axis is height (nearest the viewer's vertical), length (longer other) and width. */
	function roles(box) {
		var up = [0, 1, 2].reduce(function (best, i) {
			return Math.abs(box.axes[i][1]) > Math.abs(box.axes[best][1]) ? i : best;
		}, 0);
		var rest = [0, 1, 2].filter(function (i) { return i !== up; });
		var longer = box.extents[rest[0]] >= box.extents[rest[1]] ? rest[0] : rest[1];
		return { up: up, length: longer, width: longer === rest[0] ? rest[1] : rest[0] };
	}

	/** Length, width and height in metres. */
	function dimensions(box, metres) {
		var r = roles(box);
		return {
			length: box.extents[r.length] * metres,
			width: box.extents[r.width] * metres,
			height: box.extents[r.up] * metres
		};
	}

	/** One unit for a set of dimensions: millimetres when nothing reaches a metre, else metres. */
	function units(d) {
		var mm = Math.max(d.length, d.width, d.height) < 1;
		var number = new Intl.NumberFormat(undefined, mm
			? { maximumFractionDigits: 1 }
			: { minimumFractionDigits: 2, maximumFractionDigits: 2 });
		return {
			unit: mm ? "mm" : "m",
			value: function (metres) { return number.format(mm ? metres * 1000 : metres); }
		};
	}

	/** "L 4.00 × W 0.40 × H 0.67 m". */
	function format(d) {
		var u = units(d);
		return $L("model.dims", null, [u.value(d.length), u.value(d.width), u.value(d.height), u.unit]);
	}

	/**
	 * A box's three dimension lines for a camera at `eye`, in model units, each with the two
	 * extension lines that tie it to the box: length and width along the bottom edges facing the
	 * camera, height up the far end of the length line.
	 */
	function dimensionLines(box, eye) {
		var r = roles(box);
		var A = box.axes;
		var c = box.centre;
		var hl = box.extents[r.length] / 2;
		var hw = box.extents[r.width] / 2;
		var hu = box.extents[r.up] / 2;
		var gap = 0.08 * Math.max(box.extents[0], box.extents[1], box.extents[2]);
		var reach = gap * 1.3;
		var toEye = [eye[0] - c[0], eye[1] - c[1], eye[2] - c[2]];
		var sl = dot(toEye, A[r.length]) < 0 ? -1 : 1;
		var sw = dot(toEye, A[r.width]) < 0 ? -1 : 1;
		var su = A[r.up][1] < 0 ? -1 : 1;
		function at(l, w, u) {
			return [0, 1, 2].map(function (k) {
				return c[k] + l * A[r.length][k] + w * A[r.width][k] + u * su * A[r.up][k];
			});
		}
		// Each line runs between two points on the box, pushed out by `gap` along `out`, and each end
		// gets an extension line from the box to just past the dimension line.
		function line(extent, start, end, out) {
			function shifted(p, by) {
				return [0, 1, 2].map(function (k) { return p[k] + by * out[k]; });
			}
			return {
				extent: extent,
				from: shifted(start, gap),
				to: shifted(end, gap),
				extensions: [[start, shifted(start, reach)], [end, shifted(end, reach)]]
			};
		}
		var alongWidth = A[r.width].map(function (v) { return sw * v; });
		var alongLength = A[r.length].map(function (v) { return sl * v; });
		return [
			line(2 * hl, at(-hl, sw * hw, -hu), at(hl, sw * hw, -hu), alongWidth),
			line(2 * hw, at(sl * hl, -hw, -hu), at(sl * hl, hw, -hu), alongLength),
			line(2 * hu, at(-sl * hl, sw * hw, -hu), at(-sl * hl, sw * hw, hu), alongWidth)
		].filter(function (l) { return l.extent > 0; });
	}

	return {
		declaredUnit: declaredUnit,
		unitScale: unitScale,
		modelBox: modelBox,
		partBox: partBox,
		dimensions: dimensions,
		units: units,
		format: format,
		dimensionLines: dimensionLines
	};
})();
