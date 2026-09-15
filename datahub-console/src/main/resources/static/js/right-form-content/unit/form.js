class UnitList extends BaseList{

	constructor(obj) {
		super(obj);
		this.network = obj.network || null;
		this.title = obj.title || $L('main.units');
		this.multiSelect = false;
		this.loadData();
		this.render();
	}

	loadData(afterLoadFn){
		Api.get('/units')
			.then( resp => resp.json())
			.then( json => {
				this.data = {
					columns: [
						{
							name: $L('name'),
							prefix: "name"
						},
						{
							name: $L('symbol'),
							prefix: "symbol"
						}
					],
					// The api lists units unordered; sort by name in the browser's own collation.
					items: json.items
						.sort((a, b) => (a.name || '').localeCompare(b.name || ''))
						.map( it => ({
							id: it.id,
							name: it.name,
							symbol: it.symbol,
							externalId: it.externalId,
						}))
				}
				this.updateTable();
			});
	}

	render() {
		super.render();
	}
}