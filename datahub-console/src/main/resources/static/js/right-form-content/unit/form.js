class UnitList extends BaseList{

	constructor(obj) {
		super(obj);
		this.network = obj.network || null;
		this.apiURL = '/api/units';
		this.title = obj.title || $L('main.units');
		this.multiSelect = false;
		this.loadData();
		this.render();
	}

	loadData(afterLoadFn){
		fetch(this.apiURL, {
			method: 'GET',
			headers: {
				'Accept': 'application/json'
			}
		})
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
					items: json.items.map( it => {
						return {
							id: it.id,
							name: it.name,
							symbol: it.symbol,
							externalId: it.externalId,
						};
					})
				}
				this.updateTable();
			});
	}

	render() {
		super.render();
	}
}