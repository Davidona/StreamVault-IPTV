(() => {
  'use strict';
  const root = document.getElementById('root'), title = document.getElementById('title'), message = document.getElementById('message');
  let csrf = sessionStorage.getItem('orderingCsrf'), labels = {}, state = null, baseline = '', undo = [], redo = [];
  let selected = new Set(), activeSection = 'PERSONAL', query = '', destination = '', destinationQuery = '', dragging = null;
  let saving = false;
  const t = key => labels[key] || '';
  const element = (tag, text, cls) => { const node = document.createElement(tag); if (text != null) node.textContent = text; if (cls) node.className = cls; return node; };
  const button = (text, fn, disabled = false) => { const el = element('button', text, 'secondary'); el.type = 'button'; el.disabled = disabled; el.addEventListener('click', fn); return el; };
  async function request(path, body = {}) {
    const headers = {'Content-Type': 'application/json'};
    if (csrf) headers['X-Ordering-CSRF'] = csrf;
    const response = await fetch('/api/' + path, {method: 'POST', headers, body: JSON.stringify(body), credentials: 'same-origin', cache: 'no-store'});
    if (!response.ok) { const error = new Error(); error.status = response.status; throw error; }
    return response.json();
  }
  const copy = x => JSON.parse(JSON.stringify(x));
  const snapshot = () => JSON.stringify({sections: state.sections, rows: state.rows, mode: state.mode, moved: state.moved});
  const dirty = () => state && snapshot() !== baseline;
  function restore(saved) { Object.assign(state, JSON.parse(saved)); selected.clear(); render(); }
  function change(fn) { const before = snapshot(); fn(); if (snapshot() !== before) { undo.push(before); if (undo.length > 50) undo.shift(); redo = []; } render(); }
  function undoChange() { if (!undo.length) return; redo.push(snapshot()); restore(undo.pop()); }
  function redoChange() { if (!redo.length) return; undo.push(snapshot()); restore(redo.pop()); }
  function currentRows() { return state.kind === 'categories' ? state.sections[activeSection] : state.rows; }
  function visible(row) { return row.name.toLocaleLowerCase().includes(query.toLocaleLowerCase()); }
  function switchSection(section) { if (activeSection !== section) { selected.clear(); activeSection = section; destination = ''; destinationQuery = ''; } }
  function move(index) {
    if (state.kind === 'categories' && !(state.editableSections || []).includes(activeSection)) return;
    const rows = currentRows(), chosen = rows.filter(row => selected.has(row.key)), remaining = rows.filter(row => !selected.has(row.key));
    if (!chosen.length || index < 0 || index > remaining.length) return;
    remaining.splice(index, 0, ...chosen);
    if (remaining.every((row, i) => row.key === rows[i].key)) return;
    change(() => { if (state.kind === 'categories') state.sections[activeSection] = remaining; else { state.rows = remaining; state.mode = 'MANUAL'; state.moved = true; } });
  }
  function togglePin(row, section) {
    if (!(state.editableSections || []).includes(section)) return;
    change(() => {
      const from = state.sections[section], toSection = section === 'PINNED' ? 'PROVIDER' : 'PINNED';
      state.sections[section] = from.filter(item => item.key !== row.key);
      const target = state.sections[toSection];
      if (toSection === 'PINNED') target.push(row);
      else {
        const rank = new Map(state.normalReference.map((key, index) => [key, index]));
        const position = target.findIndex(item => (rank.get(item.key) ?? Infinity) > (rank.get(row.key) ?? Infinity));
        target.splice(position < 0 ? target.length : position, 0, row);
      }
    });
    selected.clear(); destination = ''; render();
  }
  function goCategories() { if (dirty() && !confirm(t('discardConfirm'))) return; loadCategories(); }
  function cancelDraft() {
    if (dirty() && !confirm(t('discardConfirm'))) return;
    baseline = snapshot();
    if (state.kind === 'categories') loadCategories(); else openChannels({key:state.categoryId});
  }
  async function loadCategories() {
    if (saving) return;
    saving = true; root.inert = true;
    try { const data = await request('categories'); state = {kind:'categories', sections:data.sections, editableSections:data.editableSections || ['PERSONAL','PINNED','PROVIDER'], normalReference:data.normalReference, revision:data.revision, fingerprint:data.fingerprint, title:data.title}; reset(); return true; }
    catch (error) { fail(error); return false; }
    finally { saving = false; root.inert = false; }
  }
  async function openChannels(row) {
    if (saving) return;
    if (dirty() && !confirm(t('discardConfirm'))) return;
    saving = true; root.inert = true;
    try { const data = await request('channels', {categoryId:row.key}); state = {...data, kind:'channels', categoryId:row.key, moved:false}; reset(); return true; }
    catch (error) { fail(error); return false; }
    finally { saving = false; root.inert = false; }
  }
  function reset() { selected.clear(); query = ''; destination = ''; destinationQuery = ''; undo = []; redo = []; baseline = snapshot(); message.textContent = ''; render(); }
  function fail(error) { message.textContent = error.status === 409 ? t('conflict') : t('error'); }
  async function save() {
    if (saving) return;
    saving = true;
    root.inert = true;
    try {
      const common = {revision:state.revision, fingerprint:state.fingerprint};
      if (state.kind === 'categories') await request('categories/save', {...common, sections:Object.fromEntries(Object.entries(state.sections).map(([k, rows]) => [k, rows.map(row => row.key)]))});
      else await request('channels/save', {...common, categoryId:state.categoryId, mode:state.mode, manuallyMoved:state.moved, keys:state.rows.map(row => row.key)});
      baseline = snapshot();
      saving = false;
      const reloaded = state.kind === 'categories' ? await loadCategories() : await openChannels({key:state.categoryId});
      if (reloaded) message.textContent = t('saved');
    } catch (error) { fail(error); }
    finally { saving = false; root.inert = false; }
  }
  async function selectMode(mode) {
    if (saving) return;
    if (dirty() && !confirm(t('discardConfirm'))) { render(); return; }
    saving = true;
    root.inert = true;
    try { const data = await request('channels/preview', {categoryId:state.categoryId, revision:state.revision, fingerprint:state.fingerprint, mode});
      change(() => { state.mode = mode; state.rows = data.rows; state.moved = false; }); selected.clear(); render();
    } catch (error) { fail(error); render(); }
    finally { saving = false; root.inert = false; }
  }
  function render() {
    root.replaceChildren(); title.textContent = state.title;
    if (state.kind === 'channels') root.append(element('p', t('count').replace(/%1\$d|%d/g, String(state.rows.filter(visible).length))));
    const navigation = element('div', null, 'toolbar');
    if (state.kind === 'channels') navigation.append(button(t('categories'), goCategories));
    navigation.append(button(t('save'), save, !dirty()), button(t('cancel'), cancelDraft, !dirty()), button(t('undo'), undoChange, !undo.length), button(t('redo'), redoChange, !redo.length)); root.append(navigation);
    if (state.kind === 'channels' && !state.fixedOrder) {
      const mode = element('select'); mode.setAttribute('aria-label', t('mode'));
      const modes = ['USE_DEFAULT','PROVIDER','TITLE_ASC','TITLE_DESC','RECENTLY_WATCHED','MOST_WATCHED'];
      if (state.hasManualOrder || state.mode === 'MANUAL') modes.push('MANUAL');
      modes.forEach(key => { const option = element('option', t(key)); option.value = key; mode.append(option); });
      mode.value = state.mode; mode.addEventListener('change', () => selectMode(mode.value)); root.append(mode);
    }
    const tools = element('div', null, 'toolbar'), search = element('input'); search.type = 'search'; search.value = query; search.placeholder = t('search'); search.setAttribute('aria-label', t('search'));
    search.addEventListener('input', () => { query = search.value; render(); const next = root.querySelector('input[type=search]'); next.focus(); next.setSelectionRange(query.length, query.length); }); tools.append(search);
    if (selected.size) tools.append(element('p', t('selected').replace(/%1\$d|%d/g, String(selected.size))));
    const canReorder = state.kind !== 'categories' || (state.editableSections || []).includes(activeSection);
    tools.append(button(t('selectResults'), () => { currentRows().filter(visible).forEach(row => selected.add(row.key)); render(); }, !canReorder));
    tools.append(button(t('top'), () => move(0), !selected.size || !canReorder));
    tools.append(button(t('bottom'), () => move(currentRows().length - selected.size), !selected.size || !canReorder));
    const position = element('input'); position.type = 'number'; position.min = '1'; position.max = String(currentRows().length - selected.size + 1);
    const positionLabel = t('position').replace(/%1\$d|%d/g, position.max); position.placeholder = positionLabel; position.setAttribute('aria-label', positionLabel); tools.append(position);
    tools.append(button(t('move'), () => move(Number(position.value) - 1), !selected.size || !canReorder));
    const destinationSearch = element('input'); destinationSearch.type = 'search'; destinationSearch.dataset.destinationSearch = 'true';
    destinationSearch.value = destinationQuery; destinationSearch.placeholder = t('destination') + ' — ' + t('search');
    destinationSearch.setAttribute('aria-label', destinationSearch.placeholder);
    destinationSearch.addEventListener('input', () => { destinationQuery = destinationSearch.value; render(); const next = root.querySelector('[data-destination-search]'); next.focus(); next.setSelectionRange(destinationQuery.length, destinationQuery.length); });
    tools.append(destinationSearch);
    const target = element('select'); target.setAttribute('aria-label', t('destination'));
    const empty = element('option', t('destination')); empty.value = ''; target.append(empty);
    currentRows().filter(row => !selected.has(row.key) && (row.key === destination || row.name.toLocaleLowerCase().includes(destinationQuery.toLocaleLowerCase())))
      .forEach(row => { const opt = element('option', row.name); opt.value = row.key; target.append(opt); });
    target.value = destination; target.addEventListener('change', () => { destination = target.value; render(); }); tools.append(target);
    const targetIndex = () => currentRows().filter(row => !selected.has(row.key)).findIndex(row => row.key === destination);
    tools.append(button(t('before'), () => move(targetIndex()), !selected.size || !destination || !canReorder));
    tools.append(button(t('after'), () => move(targetIndex() + 1), !selected.size || !destination || !canReorder)); root.append(tools);
    const sections = state.kind === 'categories' ? ['PERSONAL','PINNED','PROVIDER'] : ['channels'];
    sections.forEach(section => {
      if (state.kind === 'categories') { const heading = button(t('SECTION_' + section), () => { switchSection(section); render(); }); heading.disabled = section === activeSection; root.append(heading); }
      const rows = state.kind === 'categories' ? state.sections[section] : state.rows, list = element('ul', null, 'list');
      rows.forEach((row, rowIndex) => {
        if (!visible(row)) return;
        const item = element('li', null, 'item'), reorderable = state.kind !== 'categories' || (state.editableSections || []).includes(section), check = element('input'); check.type = 'checkbox'; check.checked = selected.has(row.key) && (state.kind !== 'categories' || activeSection === section); check.disabled = !reorderable; check.setAttribute('aria-label', row.name);
        check.addEventListener('change', () => { if (state.kind === 'categories') switchSection(section); if (check.checked) selected.add(row.key); else selected.delete(row.key); render(); });
        const handle = button('↕', () => { if (state.kind === 'categories') switchSection(section); selected.add(row.key); render(); }, !!query || !reorderable);
        handle.setAttribute('aria-label', t('move') + ': ' + row.name); handle.draggable = !query && reorderable;
        handle.addEventListener('dragstart', event => {
          if (query) { event.preventDefault(); return; }
          if (state.kind === 'categories') switchSection(section);
          if (!selected.has(row.key)) { selected.clear(); selected.add(row.key); }
          dragging = {key:row.key, section}; event.dataTransfer.effectAllowed = 'move'; event.dataTransfer.setData('text/plain', row.key);
        });
        handle.addEventListener('dragend', () => { dragging = null; render(); });
        item.addEventListener('dragover', event => { if (dragging && dragging.section === section && !selected.has(row.key)) event.preventDefault(); });
        item.addEventListener('drop', event => {
          if (!dragging || dragging.section !== section || selected.has(row.key)) return;
          event.preventDefault(); const remaining = currentRows().filter(candidate => !selected.has(candidate.key));
          const index = remaining.findIndex(candidate => candidate.key === row.key);
          if (index >= 0) move(index + (event.clientY > item.getBoundingClientRect().top + item.offsetHeight / 2 ? 1 : 0));
          dragging = null;
        });
        item.append(handle, check, element('span', String(rowIndex + 1) + '. ' + row.name + (row.hidden ? ' (' + t('hidden') + ')' : '')));
        if (state.kind === 'categories' && section !== 'PERSONAL' && reorderable) item.append(button(t(section === 'PINNED' ? 'unpin' : 'pin'), () => togglePin(row, section)));
        if (state.kind === 'categories' && row.editable) item.append(button(t('channels'), () => openChannels(row))); list.append(item);
      }); root.append(list);
    });
  }
  window.addEventListener('beforeunload', event => { if (dirty()) { event.preventDefault(); event.returnValue = ''; } });
  (async () => {
    try {
      const fragment = new URLSearchParams(location.hash.slice(1)), secret = fragment.get('pair');
      if (secret) { history.replaceState(null, '', '/'); csrf = null; sessionStorage.removeItem('orderingCsrf'); const result = await request('pair', {secret}); csrf = result.csrf; sessionStorage.setItem('orderingCsrf', csrf); }
      labels = await request('labels'); document.documentElement.lang = labels.language; document.documentElement.dir = labels.direction;
      await loadCategories();
      const initial = document.body.dataset.category;
      if (initial && state) { const row = Object.values(state.sections).flat().find(item => item.key === initial && item.editable); if (row) await openChannels(row); }
    } catch (error) { message.textContent = t('error') || message.dataset.error; }
  })();
})();
