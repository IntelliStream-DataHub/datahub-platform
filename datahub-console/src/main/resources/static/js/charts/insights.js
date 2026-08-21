
class InsightsChart {

	static Y_AXIS_WIDTH_PX = 50;
	static Y_AXIS_GAP_PX = 10;

	// Focused-value readout below the x-axis (appendFocusedLineValues): each series
	// block renders four text lines (time/avg/max/min) spanning 64px from the color
	// swatch to the last baseline, so rows are stacked FOCUS_ROW_HEIGHT_PX apart.
	// FOCUS_TOP_GAP_PX is the air between the plot bottom and the first row (the
	// x-axis tick labels live there). render() sizes margin.bottom from these.
	static FOCUS_ROW_HEIGHT_PX = 68;
	static FOCUS_TOP_GAP_PX = 36;

	// Aggregation buckets the backend's DatapointService understands. Order
	// matters: smallest first. _pickGranularityForSpan walks the list and
	// returns the first bucket large enough to keep the response near 500
	// points for the requested window.
	static GRANULARITY_BUCKETS = [
		{ name: '1 sec',   sec: 1 },
		{ name: '1 min',   sec: 60 },
		{ name: '5 min',   sec: 300 },
		{ name: '15 min',  sec: 900 },
		{ name: '30 min',  sec: 1800 },
		{ name: '1 hour',  sec: 3600 },
		{ name: '6 hour',  sec: 21600 },
		{ name: '12 hour', sec: 43200 },
		{ name: '1 day',   sec: 86400 },
		{ name: '1 week',  sec: 604800 },
		{ name: '1 month', sec: 2592000 },   // ~30d; ClickHouse toStartOfInterval uses calendar months
		{ name: '1 year',  sec: 31536000 }   // ~365d; calendar years server-side
	];

	constructor(obj){

		this.llValue = obj.llValue || null;
		this.hhValue = obj.hhValue || null;
		this.dataSets = [];

		// TODO: figure out why is this hack needed
		this.idleTimeout = null;

		this.container = obj.container;
		if(this.container == null){
			console.error("Missing container element. Please add it with: new InsightsChart({container: myDOMElement }).")
			throw Error();
		}
		this.container.innerHTML = "";

		// set the dimensions and margins of the graph
		// The left margin is a bit large, so we don't get any overflow issues with large numbers
		this.margin = {top: 40, right: 14, bottom: 120, left: 56};
		this.width = this._availableWidth() - this.margin.left - this.margin.right;
		this.height = 400 - this.margin.top - this.margin.bottom;

		this.svg = null;
		this.refreshSVG();
		this.lastLineColor = null;
		this.lineColorState = new LineColorState();

		// Timestamps from the server are ISO 8601 with a Z (UTC). d3.isoParse
		// honors the Z and returns a Date for the correct UTC instant; the
		// chart then displays in the user's local zone via d3.timeFormat,
		// which is what we want. d3.timeParse with a literal "Z" in the
		// format string ignored the Z and treated the digits as local — that
		// shifted incoming data by the user's offset.
		const parseUtcWithoutZ = d3.utcParse("%Y-%m-%dT%H:%M:%S.%L");
		const parseUtcWithoutZAndMs = d3.utcParse("%Y-%m-%dT%H:%M:%S");
		this.timeParser = (dateString) => {
			if (!dateString) return null;
			// Z-suffixed (or with offset) — let the standard ISO parser handle it.
			const fromIso = d3.isoParse(dateString);
			if (fromIso) return fromIso;
			// Server occasionally returns a zone-less ISO string; treat as UTC.
			return parseUtcWithoutZ(dateString) || parseUtcWithoutZAndMs(dateString);
		};

		this.tickTimeFormat = d3.timeFormat("%H:%M:%S");
		this.isDataFiltered = false;
		// True after a zoom refetch escalated to raw datapoints. Subsequent
		// zoom-ins land inside the raw window already on the client, so we
		// skip the HTTP roundtrip and just swap the x-domain.
		this._hasRawData = false;
		// When set, createXAndYScales uses this for the x-axis domain so the
		// chart always spans the user-selected window — not just the range of
		// timestamps that happened to come back in the response.
		this.timeWindow = null;

		// Live-streaming state. When liveMode is on, appendLiveDatapoint() pushes
		// incoming raw points onto the datasets and commitLiveUpdate() slides a
		// rolling window of liveWindowMs so "now" stays pinned to the right edge.
		this.liveMode = false;
		this.liveWindowMs = null;
		// When liveGrow is true the window start is anchored (liveAnchor) and only the
		// right edge follows now, so the chart grows as datapoints arrive instead of
		// scrolling a fixed-width window.
		this.liveGrow = false;
		this.liveAnchor = null;
		this._lastLiveRender = 0;
		this._liveRenderPending = false;
	}

	// Slice a dataset to points falling within the current x-axis domain
	// (with a 10% margin on each side so lines extend cleanly past the edge).
	// Raw datasets can hold tens of thousands of points; rendering them all
	// to an SVG <path> turns each transition into a string-interpolation
	// pig. d3.bisector keeps this O(log n).
	_visibleData(dataSet){
		const data = dataSet && dataSet.data;
		if (!data || data.length === 0) return data || [];
		const dom = this.x && this.x.domain ? this.x.domain() : null;
		if (!Array.isArray(dom) || !(dom[0] instanceof Date) || !(dom[1] instanceof Date)) {
			return data;
		}
		const start = dom[0].getTime();
		const end = dom[1].getTime();
		if (!(end > start)) return data;
		const margin = (end - start) * 0.1;
		const lo = new Date(start - margin);
		const hi = new Date(end + margin);
		const bisect = d3.bisector(d => d.timestamp).left;
		const i0 = Math.max(0, bisect(data, lo) - 1);
		const i1 = Math.min(data.length, bisect(data, hi) + 1);
		return data.slice(i0, i1);
	}

	// Pin the x-axis to a fixed [start, end] window. Pass two Date objects.
	// Picking a new time window also clears any active brush zoom so the next
	// render shows the full window, not the stale zoom range.
	setTimeWindow(start, end){
		if(start && end){
			this.timeWindow = { start: start, end: end };
		} else {
			this.timeWindow = null;
		}
		this.zoomDomain = null;
	}

	// Pick a d3 time interval + format string sized to the current x-axis
	// span. Falls back to ~8 ticks with HH:MM:SS when no domain is set yet.
	// Used for both the bottom axis labels and the vertical gridline ticks.
	_pickAxisConfig(){
		const domain = this.x && this.x.domain ? this.x.domain() : null;
		const start = domain && domain[0] instanceof Date ? domain[0]
			: (this.timeWindow ? this.timeWindow.start : null);
		const end = domain && domain[1] instanceof Date ? domain[1]
			: (this.timeWindow ? this.timeWindow.end : null);
		if(!(start instanceof Date) || !(end instanceof Date)){
			return { interval: null, count: 8, format: '%H:%M:%S' };
		}
		const span = (end - start) / 1000; // seconds
		// Each rule targets ~6–10 visible ticks for the corresponding span.
		// Below the 30-second mark we drop into millisecond-grained ticks so
		// raw points (which can be sub-second) line up with axis labels.
		if(span < 0.2)                  return { interval: d3.timeMillisecond.every(20),  format: '%S.%L' };
		if(span < 0.5)                  return { interval: d3.timeMillisecond.every(50),  format: '%S.%L' };
		if(span < 1)                    return { interval: d3.timeMillisecond.every(100), format: '%S.%L' };
		if(span < 2)                    return { interval: d3.timeMillisecond.every(200), format: '%S.%L' };
		if(span < 5)                    return { interval: d3.timeMillisecond.every(500), format: '%S.%L' };
		if(span < 10)                   return { interval: d3.timeSecond.every(1),        format: '%H:%M:%S' };
		if(span < 30)                   return { interval: d3.timeSecond.every(1),        format: '%H:%M:%S' };
		if(span < 60)                   return { interval: d3.timeSecond.every(5),  format: '%H:%M:%S' };
		if(span < 5 * 60)               return { interval: d3.timeSecond.every(30), format: '%H:%M:%S' };
		if(span < 15 * 60)              return { interval: d3.timeMinute.every(1),  format: '%H:%M' };
		if(span < 60 * 60)              return { interval: d3.timeMinute.every(5),  format: '%H:%M' };
		if(span < 6 * 60 * 60)          return { interval: d3.timeMinute.every(30), format: '%H:%M' };
		if(span < 24 * 60 * 60)         return { interval: d3.timeHour.every(2),    format: '%H:%M' };
		if(span < 7 * 24 * 60 * 60)     return { interval: d3.timeHour.every(12),   format: '%a %H:%M' };
		if(span < 31 * 24 * 60 * 60)    return { interval: d3.timeDay.every(2),     format: '%m-%d' };
		if(span < 92 * 24 * 60 * 60)    return { interval: d3.timeWeek.every(1),    format: '%m-%d' };
		if(span < 366 * 24 * 60 * 60)   return { interval: d3.timeMonth.every(1),   format: '%Y-%m' };
		return { interval: d3.timeMonth.every(2), format: '%Y-%m' };
	}

	_applyAxisTicks(axis, withFormat){
		const cfg = this._pickAxisConfig();
		// Drop D3's default outer tick caps (tickSizeOuter=6): those are the little
		// vertical stubs at each end of the domain path (the "black line" at the right
		// edge of the SVG). A straight axis line without end caps reads cleaner.
		axis.tickSizeOuter(0);
		if(cfg.interval) axis.ticks(cfg.interval); else axis.ticks(cfg.count);
		if(withFormat) axis.tickFormat(d3.timeFormat(cfg.format));
		else axis.tickFormat('');
		return axis;
	}

	// Pick the smallest aggregation bucket that keeps the response around 500
	// points for the given span (seconds). Names match the granularity the
	// backend's DatapointService accepts. Used by the brush-zoom refetch so
	// zooming into a long range re-pulls data at finer resolution instead of
	// trying to download raw points for the whole window. Exposed as a static
	// so callers (e.g. the timeseries explore page) can size their initial
	// fetch with the same algorithm.
	static pickGranularityForSpan(seconds){
		const buckets = InsightsChart.GRANULARITY_BUCKETS;
		const target = seconds / 500;
		for(const b of buckets){
			if(b.sec >= target) return b.name;
		}
		return buckets[buckets.length - 1].name;
	}
	_pickGranularityForSpan(seconds){
		return InsightsChart.pickGranularityForSpan(seconds);
	}

	// Width inside the container's content box, i.e. clientWidth minus horizontal
	// padding. Using clientWidth alone made the SVG bleed into the padding, which
	// pushed the flex item wider on each render and stole width from the left menu.
	_availableWidth(){
		const cs = window.getComputedStyle(this.container);
		const padX = (parseFloat(cs.paddingLeft) || 0) + (parseFloat(cs.paddingRight) || 0);
		return Math.max(0, this.container.clientWidth - padX);
	}

	refreshSVG(){
		if(this.svg){
			this.container.innerHTML = "";
		}
		// append the svg object to the container
		this.svg = d3.select(this.container)
			.append("svg:svg")
			.attr("width", this.width + this.margin.left + this.margin.right)
			.attr("height", this.height + this.margin.top + this.margin.bottom)
			.append("g")
			.attr("transform",
				"translate(" + this.margin.left + "," + this.margin.top + ")");

		// set the ranges
		this.x = d3.scaleTime().range([0, this.width]);
		this.y = d3.scaleLinear().range([this.height, 0]);

		// Define the div for the tooltip. Created once per chart instance and reused
		// across re-renders — refreshSVG runs on every render() (and several times a
		// second in live mode), so appending a fresh div each time would leak a
		// growing pile of .tooltip nodes onto <body>.
		if(!this.tooltip){
			this.tooltip = d3.select("body").append("div")
				.attr("class", "tooltip")
				.style("opacity", 0);
		}
	}

	appendGridLinesToChart(){
		// add the X gridlines
		this.vertialGridGroup = this.svg.append("g")
			.attr("class", "grid")
			.attr("transform", "translate(0," + this.height + ")")
			;
	}

	setMaxAndMinValue(){
		// Per-dataset extents so each series gets its own y-scale and a series in
		// degC and one in % don't share an axis.
		this.dataSets.forEach( dataSet => {
			const currentYMax = d3.max(dataSet.data, d => d.max);
			const currentYMin = d3.min(dataSet.data, d => d.min);
			dataSet.maxY = (currentYMax === undefined) ? 1 : currentYMax;
			dataSet.minY = (currentYMin === undefined) ? 0 : currentYMin;
		});
	}

	render(){
		// Reserve one column on the left margin for each dataset's y-axis
		// (Y_AXIS_WIDTH_PX wide, with Y_AXIS_GAP_PX air between adjacent columns)
		// before refreshing the SVG. Plot width shrinks accordingly.
		const numAxes = Math.max(1, this.dataSets.length);
		const axisColumnWidth = InsightsChart.Y_AXIS_WIDTH_PX;
		const axisGap = InsightsChart.Y_AXIS_GAP_PX;
		const baseLeftPad = 16;
		this.margin.left = baseLeftPad + numAxes * axisColumnWidth + Math.max(0, numAxes - 1) * axisGap;
		this.width = this._availableWidth() - this.margin.left - this.margin.right;

		// The focused-value readout stacks two series per row, each block
		// FOCUS_ROW_HEIGHT_PX tall. Size the bottom margin to fit every row: the
		// SVG grows downward while the plot height (fixed at construction) stays
		// put. Floor at 120 so 1-2 series keep the original layout.
		const readoutRows = Math.ceil(Math.max(1, this.dataSets.length) / 2);
		this.margin.bottom = Math.max(120,
			InsightsChart.FOCUS_TOP_GAP_PX + readoutRows * InsightsChart.FOCUS_ROW_HEIGHT_PX + 8);

		// Re-render from scratch every call. With per-dataset y-axes the left
		// margin width depends on dataset count, so leftover groups from a
		// previous render would land at the wrong x offset.
		this.refreshSVG();
		this.appendGridLinesToChart();
		this.appendFocusLine();
		this.createXAndYScales();
		this.appendClipPath();
		this.appendBrush();
		this.appendLineGroup();
		this.drawLine();
		this.appendBrushToLine();
		this.appendFocusLineObject();
		this.appendFocusedLineValues();
		this.addMouseEvents();
	}

	// Upper bound on simultaneously visible series — capped by the number
	// of distinct colors LineColorState can hand out. Pages use this to
	// gate the "select another timeseries" UX with a warning. Exposed as
	// a static so callers can check the cap before any chart instance
	// exists (e.g. while the first fetch is still pending).
	static maxDataSets(){
		return LineColorState.ALL_COLORS.length;
	}
	maxDataSets(){
		return InsightsChart.maxDataSets();
	}

	addDataSet(timeserieId, data){
		if(data.length === 0){
			alert("This timeserie returned 0 data points");
		}
		const mapped = data.datapoints.map( it => {
			return {
				timestamp : this.timeParser(it.timestamp),
				min : Number(it.min),
				max : Number(it.max),
				average: Number(it.average)
			};
		});
		// Idempotent by series id: two fetches racing for the same row (e.g. a slow request
		// outliving a re-select) must not push a duplicate line in a second color. Replace the
		// existing series' data in place and keep its color instead.
		const existing = this.dataSets.find( d => String(d.timeserieId) === String(timeserieId) );
		if(existing){
			existing.unit = data.unit || existing.unit || "";
			existing.data = mapped;
			return existing.color;
		}
		this.lastLineColor = this.lineColorState.pickColor();
		this.dataSets.push({
			timeserieId: timeserieId,
			color: this.lastLineColor,
			unit: data.unit || "",
			data: mapped
		});
		return this.lastLineColor;
	}

	removeDataSet(timeserieId){
		// Compare as strings: addDataSet stores whatever value the page passed in
		// (typically the externalId string from a data-external-id attribute), so
		// a numeric coercion would never match for non-numeric externalIds.
		const target = String(timeserieId);
		for(let i = 0; i < this.dataSets.length; i++){
			if(String(this.dataSets[i].timeserieId) === target){
				this.lineColorState.makeAvailable(this.dataSets[i].color);
				this.dataSets.splice(i, 1);
				this.refreshSVG();
				if(this.dataSets.length > 0){
					this.render();
				}
				return;
			}
		}
	}

	// Swap an existing series' data without changing its color/identity. Used when
	// the time range changes — the caller still needs to invoke render() afterwards.
	replaceDataSetData(timeserieId, data){
		const target = String(timeserieId);
		const ds = this.dataSets.find( d => String(d.timeserieId) === target );
		if(!ds) return false;
		if(data && data.unit) ds.unit = data.unit;
		const datapoints = (data && data.datapoints) || [];
		ds.data = datapoints.map( it => ({
			timestamp : this.timeParser(it.timestamp),
			min : Number(it.min),
			max : Number(it.max),
			average: Number(it.average)
		}));
		delete ds.originalData;
		this.isDataFiltered = false;
		this._hasRawData = false;
		return true;
	}

	// === Live streaming ===========================================================
	// Turn on live mode. Pass a `windowMs` for a rolling fixed-width window (the default,
	// 5 min) or grow=true for a window anchored at "now" that grows as points arrive.
	// Incoming points are raw single readings, so there is no min/max band to draw — flip
	// into line-only mode (the same flag the raw-zoom path uses). Call commitLiveUpdate()
	// after feeding a batch of points to slide/grow the window and redraw. Safe to call
	// again while already live to switch window type.
	// Replace a series' data with RAW datapoints (single value, no min/max band) and keep the
	// chart in raw/line mode. Used to seed the live window so the live view is raw throughout.
	seedRawData(timeserieId, data){
		const ds = this.dataSets.find( d => String(d.timeserieId) === String(timeserieId) );
		if(!ds) return false;
		if(data && data.unit) ds.unit = data.unit;
		const datapoints = (data && data.datapoints) || [];
		ds.data = datapoints.map( it => {
			const v = Number(it.value);
			return { timestamp: this.timeParser(it.timestamp), min: v, max: v, average: v };
		});
		delete ds.originalData;
		this.isDataFiltered = false;
		this._hasRawData = true;
		return true;
	}

	enableLiveMode(windowMs, grow){
		this.liveMode = true;
		this._hasRawData = true;
		this._lastLiveRender = 0;
		this.liveGrow = !!grow;
		this.liveWindowMs = this.liveGrow ? null : ((windowMs && windowMs > 0) ? windowMs : 5 * 60_000);
		if(this.liveGrow) this.liveAnchor = Date.now();
		this._slideWindow();
	}

	disableLiveMode(){
		this.liveMode = false;
		this.liveWindowMs = null;
		this.liveGrow = false;
		this.liveAnchor = null;
		this._liveRenderPending = false;
	}

	// Pin the window to follow now: [now - liveWindowMs, now] for a rolling window, or
	// [liveAnchor, now] for a growing one. Returns the window start (ms).
	_slideWindow(){
		const end = new Date();
		const start = this.liveGrow
			? new Date(this.liveAnchor || end.getTime())
			: new Date(end.getTime() - this.liveWindowMs);
		this.setTimeWindow(start, end);
		return start.getTime();
	}

	// Append one live raw datapoint to a series without re-rendering (the caller batches
	// appends and then calls commitLiveUpdate once). Non-numeric values (TEXT/MIXED
	// timeseries) are skipped — the chart is numeric. min/max/average are all the raw value.
	appendLiveDatapoint(timeserieId, point){
		const ds = this.dataSets.find( d => String(d.timeserieId) === String(timeserieId) );
		if(!ds || !point) return false;
		const ts = this.timeParser(point.timestamp);
		if(!ts) return false;
		const value = Number(point.value);
		if(!Number.isFinite(value)) return false;
		const entry = { timestamp: ts, min: value, max: value, average: value };
		const data = ds.data || (ds.data = []);
		// Live points usually arrive newest-last, so append; otherwise insert in order
		// (the line generator and bisector slicing both assume timestamp-sorted data).
		if(data.length === 0 || ts.getTime() >= data[data.length - 1].timestamp.getTime()){
			data.push(entry);
		} else {
			const i = d3.bisector(d => d.timestamp).left(data, ts);
			data.splice(i, 0, entry);
		}
		return true;
	}

	// Slide the window to now, trim points that scrolled off, and redraw. Throttled so a
	// burst of frames coalesces into at most ~5 renders/sec.
	commitLiveUpdate(){
		if(!this.liveMode || this.dataSets.length === 0) return;
		if(this._liveRenderPending) return;
		const minGapMs = 200;
		const sinceLast = Date.now() - this._lastLiveRender;
		const run = () => {
			this._liveRenderPending = false;
			this._lastLiveRender = Date.now();
			if(!this.liveMode || this.dataSets.length === 0) return;
			const minTime = this._slideWindow();
			this._trimLiveData(minTime);
			this.render();
		};
		if(sinceLast >= minGapMs){
			run();
		} else {
			this._liveRenderPending = true;
			setTimeout(run, minGapMs - sinceLast);
		}
	}

	// Drop points that scrolled off the left of the rolling window (keeping a 10% margin so
	// the line exits cleanly) and hard-cap each series so a fast stream can't grow unbounded.
	_trimLiveData(minTimeMs){
		const MAX_POINTS = 10000;
		const margin = this.liveWindowMs ? this.liveWindowMs * 0.1 : 0;
		const cutoff = minTimeMs - margin;
		this.dataSets.forEach( ds => {
			let data = ds.data;
			if(!data || data.length === 0) return;
			let drop = 0;
			while(drop < data.length && data[drop].timestamp.getTime() < cutoff) drop++;
			if(drop > 0) data = ds.data = data.slice(drop);
			if(data.length > MAX_POINTS) ds.data = data.slice(data.length - MAX_POINTS);
		});
	}

	getColor(timeserieId){
		const tsId = String(timeserieId);
		const ds = this.dataSets.find( d => String(d.timeserieId) === tsId );
		return ds ? ds.color : null;
	}

	// If the new color is already in use by a different series, swap colors
	// between the two series — that keeps every series visually distinct
	// without forcing the user to free a color first.
	setColor(timeserieId, newColor){
		const tsId = String(timeserieId);
		const target = this.dataSets.find( d => String(d.timeserieId) === tsId );
		if(!target || target.color === newColor) return;
		if(!this.lineColorState.allColors.includes(newColor)) return;

		const other = this.dataSets.find( d => d.color === newColor );
		const oldColor = target.color;
		if(other){
			other.color = oldColor;
			target.color = newColor;
		} else {
			this.lineColorState.makeAvailable(oldColor);
			this.lineColorState.take(newColor);
			target.color = newColor;
		}
		this.refreshSVG();
		this.render();
	}

	appendFocusLine(){
		// Add focus line group
		this.focus = this.svg.append("g")
			.attr("class", "focus")
			.style("display", "none");
	}

	createXAndYScales() {

		this.setMaxAndMinValue();

		// Add X axis --> it is a date format. Shared across all lanes.
		// Resolution order: zoomDomain (active brush zoom) > timeWindow (the
		// user-selected start/end window) > extent of the data itself. The
		// zoomDomain takes priority so a fresh render() during a zoom keeps the
		// zoomed-in view instead of snapping back to timeWindow.
		const xDomain = this.zoomDomain
			? [ this.zoomDomain[0], this.zoomDomain[1] ]
			: this.timeWindow
				? [ this.timeWindow.start, this.timeWindow.end ]
				: d3.extent(this.dataSets[0].data, function(d) { return d.timestamp; });
		this.x = d3.scaleTime()
			.domain(xDomain)
			.range([ 0, this.width ]);
		this.xAxis = this.svg.append("g")
			.attr("class", 'tick-group')
			.attr("transform", "translate(0," + this.height + ")")
			.call(this._applyAxisTicks(d3.axisBottom(this.x), true))
		;

		this.vertialGridGroup.call(
			this._applyAxisTicks(d3.axisBottom(this.x).tickSize(-this.height), false)
		);

		// All series overlay the full plot area so visual correlations between e.g.
		// temperature and pressure pop out. Each dataset keeps its own y-scale and
		// gets its own y-axis stacked horizontally to the left of the plot, with
		// Y_AXIS_GAP_PX air between adjacent axis columns. Axis 0 sits closest to
		// the plot; axis i is offset by i * (Y_AXIS_WIDTH_PX + Y_AXIS_GAP_PX).
		const axisColumnWidth = InsightsChart.Y_AXIS_WIDTH_PX;
		const axisGap = InsightsChart.Y_AXIS_GAP_PX;

		this.dataSets.forEach( (dataSet, i) => {
			const offsetX = i * (axisColumnWidth + axisGap);

			dataSet.y = d3.scaleLinear()
				.domain([ dataSet.minY, dataSet.maxY ])
				.range([ this.height, 0 ]);

			dataSet.yAxisGroup = this.svg.append("g")
				.attr("class", "y-axis ts-" + dataSet.timeserieId + " " + dataSet.color)
				.attr("transform", "translate(" + (-offsetX) + ", 0)")
				.call(d3.axisLeft(dataSet.y).ticks(5));

			if(dataSet.unit){
				this.svg.append("text")
					.attr("class", "y-axis-unit ts-" + dataSet.timeserieId + " " + dataSet.color)
					.attr("text-anchor", "end")
					.attr("x", -offsetX - 8)
					.attr("y", -8)
					.text(dataSet.unit);
			}
		});
	}

	appendClipPath(){
		// Add a clipPath: everything out of this area won't be drawn.
		this.clip = this.svg.append("defs").append("svg:clipPath")
			.attr("id", "clip")
			.append("svg:rect")
			.attr("width", this.width )
			.attr("height", this.height )
			.attr("x", 0)
			.attr("y", 0);
	}

	appendBrush(){
		// Add the brush feature using the d3.brush function
		// initialise the brush area: start at 0,0 and finishes at
		// width,height: it means I select the whole graph area.
		// While the user drags we update a centered label with the live
		// duration of the current selection ("3 minutes", "5 days", …).
		// On end we hide the label and run the existing updateChart.
		this.brush = d3.brushX()
			.extent( [ [0,0], [this.width, this.height] ] )
			.on("brush", (e) => this._onBrushMove(e))
			.on("end", (e) => {
				if (this.brushLabel) this.brushLabel.style("display", "none");
				this.updateChart(e);
			});

		this.brushLabel = this.svg.append("text")
			.attr("class", "dh-brush-label")
			.style("display", "none");
	}

	_onBrushMove(e){
		if (!e || !e.selection || !this.brushLabel) {
			if (this.brushLabel) this.brushLabel.style("display", "none");
			return;
		}
		const [x0px, x1px] = e.selection;
		const x0 = this.x.invert(x0px);
		const x1 = this.x.invert(x1px);
		this.brushLabel
			.style("display", null)
			.attr("x", (x0px + x1px) / 2)
			.attr("y", -8)
			.attr("text-anchor", "middle")
			.text(this._formatBrushDuration(x1 - x0))
			.raise();
	}

	// Format a duration in ms as a localized "<n> <unit>" string. Uses
	// Intl.NumberFormat's unit style so plurals follow the page locale
	// automatically (English: "5 minutes"; Norwegian: "5 minutter").
	_formatBrushDuration(ms){
		const locale = document.documentElement.lang || 'en';
		const sec = ms / 1000;
		let value, unit;
		if (sec < 1)              return Math.max(0, Math.round(ms)) + ' ms';
		if (sec < 60)             { value = Math.round(sec);                unit = 'second'; }
		else if (sec < 3600)      { value = Math.round(sec / 60);           unit = 'minute'; }
		else if (sec < 86400)     { value = Math.round(sec / 3600);         unit = 'hour'; }
		else if (sec < 604800)    { value = Math.round(sec / 86400);        unit = 'day'; }
		else if (sec < 2592000)   { value = Math.round(sec / 604800);       unit = 'week'; }
		else if (sec < 31536000)  { value = Math.round(sec / 2592000);      unit = 'month'; }
		else                      { value = Math.round(sec / 31536000);     unit = 'year'; }
		try {
			return new Intl.NumberFormat(locale, {
				style: 'unit', unit: unit, unitDisplay: 'long'
			}).format(value);
		} catch (e) {
			return value + ' ' + unit + (value === 1 ? '' : 's');
		}
	}

	// A function that update the chart for given boundaries.
	// Idle gate on empty selection, parallel refetch when the window is sparse,
	// otherwise straight x-domain swap; always falls through to animated axis
	// + line transitions at the bottom.
	//
	// `_reentered` is true when called recursively from a finished refetch —
	// in that case isDataFiltered is left alone so the body falls through to
	// the else branch that swaps the x-domain. New brush events come in with
	// _reentered=false and clear the flag, so each brush can refetch again.
	updateChart(e, _reentered) {

		const extent = e === undefined ? null : e.selection;
		if(!_reentered && extent){
			this.isDataFiltered = false;
		}

		if(!extent){
			if (!this.idleTimeout) {
				return this.idleTimeout = setTimeout(
					() => { this.idleTimeout = null; },
					350
				);
			}

			// Restore the page-supplied data, drop raw mode, snap the
			// x-domain back to the full window — all *before* drawLine
			// so the freshly-built paths already reflect the final view.
			// Then redraw axes inline (no transition) and exit; the user
			// asked to reset, so jumping straight to the full view is
			// what they want, and it skips the cost of interpolating
			// huge `d` strings back to extent.
			this.dataSets.forEach( ds => {
				if(ds.originalData){
					ds.data = ds.originalData;
					delete ds.originalData;
				}
			});
			this._hasRawData = false;
			if(this.timeWindow){
				this.x.domain([ this.timeWindow.start, this.timeWindow.end ]);
			} else if (this.dataSets.length > 0) {
				const firstDataCollection = this.dataSets[0].data;
				this.x.domain([
					firstDataCollection[0].timestamp,
					firstDataCollection[firstDataCollection.length -1].timestamp
				]);
			}
			this.zoomDomain = null;

			this.drawLine();

			this.xAxis.call(
				this._applyAxisTicks(d3.axisBottom(this.x), true)
			);
			this.vertialGridGroup.call(
				this._applyAxisTicks(d3.axisBottom(this.x).tickSize(-this.height), false)
			);

			// Re-attach dblclick — the bottom transition block (which
			// normally re-attaches it on every updateChart) is skipped now.
			this.svg.on("dblclick", (e) => {
				this.updateChart(e);
				this.isDataFiltered = false;
			});
			return;
		} else {

			const x0 = this.x.invert(extent[0]);
			const x1 = this.x.invert(extent[1]);

			if(extent[0] === extent[1]) {
				// Selection too small, nothing to do
				this.line.select(".brush").call(this.brush.move, null);
				this.idleTimeout = null;
				return ;
			}

			const maxCountInWindow = this.dataSets.reduce( (acc, ds) => {
				const count = ds.data.filter(d => d.timestamp >= x0 && d.timestamp <= x1).length;
				return count > acc ? count : acc;
			}, 0);

			// Once we've escalated to raw, subsequent zoom-ins land inside the
			// raw window we already have — there's nothing finer to fetch.
			// Skip the refetch path and just swap the x-domain.
			if(maxCountInWindow < 50 && !this.isDataFiltered && !this._hasRawData){
				// Refetch each series at a finer granularity sized to the zoom
				// window so we stay around 500 points. If even that comes back
				// sparse (< 100 points in the densest series), escalate to raw
				// — at narrow windows the granularity bucket can be coarser
				// than the actual sample interval and aggregation just hides
				// individual readings.
				const spanSec = Math.max(1, (x1 - x0) / 1000);
				const granularity = this._pickGranularityForSpan(spanSec);
				console.debug("zoom refetch at granularity:", granularity);
				// x0/x1 are Date instants from this.x.invert(...); just emit
				// them as UTC ISO. The picker uses the same convention so the
				// brush refetch and the original fetch agree on the window.
				const startIso = x0.toISOString();
				const endIso = x1.toISOString();
				const csrfHeader = document.querySelector('meta[name="_csrf_header"]').content;
				const csrfToken = document.querySelector('meta[name="_csrf"]').content;
				const fetchOne = (dataSet, extras) => fetch('/api/timeseries/data/by-hours-ago', {
					method: 'POST',
					headers: {
						'Accept': 'application/json',
						'Content-type': 'application/json',
						[csrfHeader]: csrfToken
					},
					body: JSON.stringify(Object.assign({
						externalId: dataSet.timeserieId,
						startTime: startIso,
						endTime: endIso
					}, extras))
				}).then(r => r.json()).then(json => ({ dataSet, json }));

				const fetchAggregated = ds => fetchOne(ds, { granularity: granularity });
				const fetchRaw = ds => fetchOne(ds, { raw: true });

				const densestCount = results => results.reduce((acc, r) => {
					const items = (r.json && r.json.items) || [];
					const c = items[0] ? (items[0].datapoints || []).length : 0;
					return c > acc ? c : acc;
				}, 0);

				const applyResults = (results, isRaw) => {
					results.forEach( ({ dataSet, json }) => {
						if(!json || !json.items || json.items.length === 0) return;
						const datapoints = json.items[0].datapoints || [];
						// Preserve the page-supplied data on first refetch; subsequent
						// refetches overwrite the zoom slice without losing the original.
						if(!dataSet.originalData) dataSet.originalData = dataSet.data;
						dataSet.data = datapoints.map( it => isRaw
							? ({
								timestamp : this.timeParser(it.timestamp),
								min : Number(it.value),
								max : Number(it.value),
								average: Number(it.value)
							})
							: ({
								timestamp : this.timeParser(it.timestamp),
								min : Number(it.min),
								max : Number(it.max),
								average: Number(it.average)
							}));
					});
				};

				// Hide the existing line + area immediately so the user gets
				// instant feedback that new data is on the way and we don't
				// run any transitions that animate the stale data into the
				// new window. drawLine after the fetch will replace these
				// elements outright.
				this.line.selectAll("path.ts-line").style("display", "none");
				this.lineArea.selectAll("path.ts-line").style("display", "none");
				// Clear the brush UI now (rather than at the bottom of the
				// function) since we're about to return.
				this.line.select(".brush").call(this.brush.move, null);
				this.idleTimeout = null;

				Promise.all(this.dataSets.map(fetchAggregated))
					.then( results => {
						if(densestCount(results) < 100){
							console.debug("aggregated response sparse, escalating to raw");
							return Promise.all(this.dataSets.map(fetchRaw))
								.then(rawResults => ({ results: rawResults, raw: true }));
						}
						return { results: results, raw: false };
					})
					.then( ({ results, raw }) => {
						applyResults(results, raw);
						// Order matters: _hasRawData and the new x-domain
						// must be set *before* drawLine so the freshly
						// painted paths already reflect the final view.
						if (raw) this._hasRawData = true;
						this.x.domain([ x0, x1 ]);
						this.zoomDomain = [ x0, x1 ];
						this.drawLine();
						// Snap axes (no morph from old domain).
						this.xAxis.call(
							this._applyAxisTicks(d3.axisBottom(this.x), true)
						);
						this.vertialGridGroup.call(
							this._applyAxisTicks(d3.axisBottom(this.x).tickSize(-this.height), false)
						);
						this.isDataFiltered = true;

						// Animate the freshly-drawn paths in. The area uses the
						// same avg → min/max grow tween as the no-refetch
						// zoom so the band visually "expands" out from the
						// average line; the line eases in via opacity so the
						// two animations feel coordinated. In raw mode the
						// area path doesn't exist (drawLine skipped it) and
						// the area select is a no-op.
						this.dataSets.forEach( dataSet => {
							const cls = ".ts-" + dataSet.timeserieId;
							const visible = this._visibleData(dataSet);

							// Line "expands" from a flat horizontal line at the
							// mean of the visible averages out to its actual
							// shape — same growth feel as the area's
							// avg → min/max tween.
							let sum = 0;
							for (const d of visible) sum += d.average;
							const baselineValue = visible.length > 0 ? sum / visible.length : 0;
							const baselineY = dataSet.y(baselineValue);
							this.line.select("path.ts-line.avg" + cls)
								.transition().duration(1000)
								.attrTween("d", () => t => {
									const gen = d3.line()
										.x( d => this.x(d.timestamp) )
										.y( d => {
											const finalY = dataSet.y(d.average);
											return baselineY + (finalY - baselineY) * t;
										});
									return gen(visible);
								});

							this.lineArea.select("path.ts-line.area" + cls)
								.transition().duration(1000)
								.attrTween("d", () => t => {
									const gen = d3.area()
										.x( d => this.x(d.timestamp) )
										.y0( d => {
											const avg = dataSet.y(d.average);
											const max = dataSet.y(d.max);
											return avg + (max - avg) * t;
										})
										.y1( d => {
											const avg = dataSet.y(d.average);
											const min = dataSet.y(d.min);
											return avg + (min - avg) * t;
										});
									return gen(visible);
								});
						});
					})
					.catch((err) => {
						console.error(err);
					});
				return;
			} else {
				this.x.domain([
					this.x.invert(extent[0]),
					this.x.invert(extent[1])
				]);
				this.zoomDomain = [ x0, x1 ];
			}
			// This remove the grey brush area as soon as the selection has been done
			this.line.select(".brush").call(this.brush.move, null);
			this.idleTimeout = null;
		}

		// Update axis and line position
		this.xAxis.transition().duration(1000).call(
			this._applyAxisTicks(d3.axisBottom(this.x), true)
		);

		this.vertialGridGroup.transition().duration(1000).call(
			this._applyAxisTicks(d3.axisBottom(this.x).tickSize(-this.height), false)
		);

		// Animate line + area per dataset; each has its own y-scale, so we
		// iterate explicitly rather than using a single selectAll. The path
		// is rebound to the visible slice (see _visibleData) before the
		// transition runs, so even with raw data the d strings being
		// interpolated stay small enough for a smooth 1s morph. Raw mode
		// has no area path (drawLine skips it), so the area select returns
		// an empty selection and the transition is a no-op.
		// Animate line + area on a no-refetch zoom (same data, smaller
		// window). The refetch branch returned early, so anything reaching
		// here is operating on already-loaded data. The area uses an
		// attrTween that grows from the average line out to min/max so the
		// top/bottom edges of the band don't shuffle from string
		// interpolation between different point counts.
		this.dataSets.forEach( dataSet => {
			const cls = ".ts-" + dataSet.timeserieId;
			const visible = this._visibleData(dataSet);

			this.line.select("path.ts-line.avg" + cls)
				.datum(visible)
				.transition()
				.duration(1000)
				.attr("d", d3.line()
					.x( d => this.x(d.timestamp) )
					.y( d => dataSet.y(d.average) )
				);

			this.lineArea.select("path.ts-line.area" + cls)
				.datum(visible)
				.transition()
				.duration(1000)
				.attrTween("d", () => t => {
					const gen = d3.area()
						.x( d => this.x(d.timestamp) )
						.y0( d => {
							const avg = dataSet.y(d.average);
							const max = dataSet.y(d.max);
							return avg + (max - avg) * t;
						})
						.y1( d => {
							const avg = dataSet.y(d.average);
							const min = dataSet.y(d.min);
							return avg + (min - avg) * t;
						});
					return gen(visible);
				});
		});

		// If user double click, reinitialize the chart
		this.svg.on("dblclick", (e) => {
			this.updateChart(e)
			this.isDataFiltered = false;
		});
	}

	appendLineGroup(){
		// Create the line variable: where both the line and the brush take place
		this.lineArea = this.svg.append('g')
			.attr("clip-path", "url(#clip)");
		this.lineArea.attr("id", "line-area-group");
		this.line = this.svg.append('g')
			.attr("clip-path", "url(#clip)");
		this.line.attr("id", "line-path-group");
	}

	drawLine(){
		// Clear existing paths once before redrawing all datasets.
		this.lineArea.selectAll("path.ts-line").remove();
		this.line.selectAll("path.ts-line").remove();

		// Each dataset is drawn with its own per-lane y-scale. Datum is the
		// visible slice — see _visibleData — so the SVG path stays small
		// even when the dataset holds tens of thousands of raw points.
		// The min/max band is rendered only for aggregated data; raw
		// datapoints only carry a single value, so the band would be
		// meaningless and its transition produces visual artifacts.
		const renderArea = !this._hasRawData;
		this.dataSets.forEach( dataSet => {
			const tsClass = "ts-" + dataSet.timeserieId;
			const visible = this._visibleData(dataSet);

			if (renderArea) {
				this.lineArea.append("path")
					.datum(visible)
					.attr("class", "ts-line area " + dataSet.color + " " + tsClass)
					.attr("d", d3.area()
						.x( d => this.x(d.timestamp) )
						.y0( d => dataSet.y(d.max) )
						.y1( d => dataSet.y(d.min) )
					);
			}

			this.line.append("path")
				.datum(visible)
				.attr("class", "ts-line avg " + dataSet.color + " " + tsClass)
				.attr("d", d3.line()
					.x( d => this.x(d.timestamp) )
					.y( d => dataSet.y(d.average) )
				);
		});

	}

	appendBrushToLine(){
		// Add the brushing
		this.line
			.append("g")
			.attr("class", "brush")
			.call(this.brush);
	}

	appendFocusLineObject(){
		this.focus.append("rect")
			.attr("class", "focus-line")
			.attr("x", 0)
			.attr("y", 0)
			.attr("height", this.height)
			.attr("width", 0.5);
	}

	appendFocusedLineValues(){

		this.lineValueGroup = this.svg.append("g")
			.attr("class", "line-focus-values");

		let startYPos = this.height + InsightsChart.FOCUS_TOP_GAP_PX;
		let startXPos = -20;
		let i = 0;

		this.dataSets.forEach( dataSet => {
			this.lineValueGroup.append("rect")
				.attr("class", "lfv-rect " + dataSet.color)
				.attr("width", 20)
				.attr("height", 14)
				.attr("x", startXPos)
				.attr("y", startYPos)
				.attr("rx", 4)
				.attr("ry", 4);

			this.lineValueGroup.append("text")
				.attr("class", `lfv-text lfv-${dataSet.timeserieId}-time`)
				.attr("x", startXPos + 25)
				.attr("y", startYPos + 16)
				.attr("text-anchor", "left");
			this.lineValueGroup.append("text")
				.attr("class", `lfv-text lfv-${dataSet.timeserieId}-avg`)
				.attr("x", startXPos + 25)
				.attr("y", startYPos + 32)
				.attr("text-anchor", "left");
			this.lineValueGroup.append("text")
				.attr("class", `lfv-text lfv-${dataSet.timeserieId}-max`)
				.attr("x", startXPos + 25)
				.attr("y", startYPos + 48)
				.attr("text-anchor", "left");
			this.lineValueGroup.append("text")
				.attr("class", `lfv-text lfv-${dataSet.timeserieId}-min`)
				.attr("x", startXPos + 25)
				.attr("y", startYPos + 64)
				.attr("text-anchor", "left");

			// Do calculation for position rendering: two series per row, advancing
			// by the full block height so rows don't overlap.
			i++;
			if(i % 2 === 0){
				startYPos += InsightsChart.FOCUS_ROW_HEIGHT_PX;
				startXPos = -20;
			} else {
				startXPos += (this.width / 2);
			}

		});

	}

	addMouseEvents(){
		this.svg
			.on("mouseover", () => { this.focus.style("display", null); })
			.on("mouseout", () => { this.focus.style("display", "none"); })
			.on("mousemove", (event) => {

				const pointer = d3.pointer(event, this.svg.nodes()[0]);
				const x0 = this.x.invert(pointer[0]);
				this.focus.attr(
					"transform",
					"translate(" + this.x(x0) + "," + 0 + ")"
				);

				this.dataSets.forEach( dataSet => {
					const idx = d3.bisector(data => {
						return data.timestamp;
					}).left(dataSet.data, x0, 1);
					const d0 = dataSet.data[idx - 1];
					const d1 = dataSet.data[idx];

					if(d1) {
						const d = x0 - d0.timestamp > d1.timestamp - x0 ? d1 : d0;

						this.lineValueGroup
							.select(`.lfv-${dataSet.timeserieId}-time`)
							.text(`Time: ${d3.timeFormat("%Y-%m-%d %H:%M:%S.%L")(d.timestamp)}${window.tzAbbr ? ' ' + window.tzAbbr(d.timestamp) : ''}`);
						this.lineValueGroup
							.select(`.lfv-${dataSet.timeserieId}-avg`)
							.text(`Avg: ${Number.parseFloat(d.average).toFixed(4)}`)
						this.lineValueGroup
							.select(`.lfv-${dataSet.timeserieId}-max`)
							.text(`Max: ${Number.parseFloat(d.max).toFixed(4)}`)
						this.lineValueGroup
							.select(`.lfv-${dataSet.timeserieId}-min`)
							.text(`Min: ${Number.parseFloat(d.min).toFixed(4)}`)
						;
					}
				});

			});
	}

}

class LineColorState{

	static ALL_COLORS = [
		"orange",
		"teal",
		"purple",
		"yellowgreen",
		"pink",
		"skyblue"
	];

	constructor(){
		this.allColors = LineColorState.ALL_COLORS;
		this.availableColors = [...this.allColors];
		this.usedColors = [];
	}

	pickColor(){
		const colorName = this.availableColors.pop();
		this.usedColors.push(colorName);
		return colorName;
	}

	makeAvailable(colorName){
		if(this.allColors.find( color => color === colorName )){
			this.usedColors =
				this.usedColors.filter( color => color !== colorName);
			this.availableColors.push(colorName);
		}
	}

	take(colorName){
		if(this.availableColors.includes(colorName)){
			this.availableColors = this.availableColors.filter( c => c !== colorName );
			this.usedColors.push(colorName);
		}
	}

}