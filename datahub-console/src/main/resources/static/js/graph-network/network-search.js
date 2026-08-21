const renderSearchResults = data => {
    const existingSearchTable = document.getElementById('left-content-asset-search-results');
    if(existingSearchTable) {
        existingSearchTable.remove();
    }
    const sectionEle = Object.assign(document.createElement('section'), {
        id: "left-content-asset-search-results",
        innerHTML: `
						<table class="listing slim objects w100 table-select">
							<thead>
								<tr>
									<th>${$L('name')}</th>
								</tr>
							</thead>
							<tbody></tbody>
						</table>`
    });
    const tableBody = sectionEle.firstElementChild.tBodies[0];
    for(let i = 0; i < data.length; i++){
        const r = tableBody.insertRow();
        r.className = 'pointer';
        r.setAttribute('data-id', data[i].id);
        const cellName = r.insertCell();
        const resourceNameCell = Object.assign(document.createElement('div'), {});
        resourceNameCell.textContent = data[i].name
        cellName.append( resourceNameCell );
        const labelCells = Object.assign(document.createElement('div'), {})

        for(let y = 0; y < data[i].labels.length; y++){
            const labelText = data[i].labels[y];
            labelCells.append(createLabel(labelText));
        }
        cellName.append( labelCells );

        r.addEventListener('click', e => {
            let resourceNetwork = new GraphNetwork({
                container: document.querySelector('[data-type="resource-network"]'),
                queryDepth: 2
            });
            resourceNetwork.loadNetwork(r.getAttribute('data-id'), false);
        });
    }
    document.querySelector('section.left-content').append(sectionEle);
    document.getElementById('section-root-asset-list').style.display = "none";
};