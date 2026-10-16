function byId(id) {
    return document.getElementById(id);
}

async function api(url, method, body) {
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 15000);
    const options = {
        method: method || 'GET',
        headers: { 'Content-Type': 'application/json' },
        signal: controller.signal
    };
    if (body) options.body = JSON.stringify(body);
    try {
        const response = await fetch(url, options);
        const contentType = response.headers.get('content-type') || '';
        let payload = null;
        if (contentType.includes('application/json')) {
            try { payload = await response.json(); }
            catch (_) { payload = null; }
        }
        if (!response.ok) {
            const message = payload?.message || (response.status === 401
                ? 'Your session has ended. Log in again.'
                : response.status === 403
                    ? 'This action is not allowed for your role.'
                    : 'Server error (' + response.status + '). Check the app terminal.');
            return { ok: false, message };
        }
        return payload ?? { ok: false, message: 'The server returned an unreadable reply. Check the app terminal.' };
    } catch (error) {
        if (error && error.name === 'AbortError') {
            return { ok: false, message: 'SmartBlock took too long to reply. Check the app terminal before trying again.' };
        }
        return { ok: false, message: 'Can’t connect to SmartBlock. Start the app and check MySQL.' };
    } finally {
        window.clearTimeout(timeout);
    }
}

// Use an in-page dialog because some browsers disable native text prompts.
function askForText(message, defaultValue = '') {
    return new Promise(resolve => {
        const backdrop = document.createElement('div');
        backdrop.className = 'input-dialog-backdrop';
        backdrop.innerHTML = `
            <section class="input-dialog" role="dialog" aria-modal="true" aria-labelledby="inputDialogTitle">
                <h2 id="inputDialogTitle">Enter details</h2>
                <p class="input-dialog-message"></p>
                <form class="input-dialog-form">
                    <textarea class="input-dialog-value" rows="4" maxlength="500" aria-label="Details" placeholder="Type your note here"></textarea>
                    <div class="input-dialog-actions">
                        <button type="button" class="btn-grey input-dialog-cancel">Cancel</button>
                        <button type="submit" class="btn-main">Continue</button>
                    </div>
                </form>
            </section>`;

        backdrop.querySelector('.input-dialog-message').textContent = message;
        const field = backdrop.querySelector('.input-dialog-value');
        field.value = defaultValue || '';
        document.body.appendChild(backdrop);

        let finished = false;
        const finish = value => {
            if (finished) return;
            finished = true;
            document.removeEventListener('keydown', onKeyDown);
            backdrop.remove();
            resolve(value);
        };
        const onKeyDown = event => {
            if (event.key === 'Escape') {
                event.preventDefault();
                finish(null);
            }
        };

        backdrop.querySelector('.input-dialog-form').addEventListener('submit', event => {
            event.preventDefault();
            finish(field.value);
        });
        backdrop.querySelector('.input-dialog-cancel').addEventListener('click', () => finish(null));
        backdrop.addEventListener('click', event => {
            if (event.target === backdrop) finish(null);
        });
        document.addEventListener('keydown', onKeyDown);
        field.focus();
        field.setSelectionRange(field.value.length, field.value.length);
    });
}

let currentRole = null;
let currentDept = null;
let myRequestCache = [];

async function doLogin() {
    const username = byId('username').value.trim();
    const password = byId('password').value;

    const result = await api('/api/login', 'POST', { username, password });

    if (!result.ok) {
        byId('loginError').textContent = result.message;
        return;
    }
    openApp(result.username, result.role, result.dept);
}

function openApp(username, role, dept) {
    currentRole = role;
    currentDept = dept || null;
    setWorkExamples(currentDept);
    byId('loginScreen').classList.add('hidden');
    byId('app').classList.remove('hidden');
    byId('whoami').textContent = username + ' (' + (role === 'CONTROL' ? 'Control Room' : (dept || 'Department')) + ')';
    showTabsForRole(role);
    loadDashboard();
    if (role === 'CONTROL') loadControlWorkAlert();
    if (role === 'DEPARTMENT') loadDepartmentStatusAlert();
}

function showTabsForRole(role) {
    document.querySelectorAll('.tab').forEach(tab => {
        const allowed = tab.dataset.roles.split(',');
        tab.classList.toggle('hidden', !allowed.includes(role));
    });
}

function setWorkExamples(dept) {
    const field = byId('fWork');
    if (!field) return;

    const examples = {
        Engineering: 'e.g. Replace damaged sleepers near a joint',
        Signal: 'e.g. Inspect signal cable near the junction',
        Traction: 'e.g. Repair a damaged overhead wire'
    };
    field.placeholder = examples[dept] || 'Describe the maintenance work';
}

async function doLogout() {
    await api('/api/logout', 'POST');
    location.reload();
}

window.setInterval(() => {
    if (!currentRole) return;
    if (currentRole === 'CONTROL') loadControlWorkAlert();
    if (currentRole === 'DEPARTMENT') loadDepartmentStatusAlert();
    const page = byId('completion');
    if (page && !page.classList.contains('hidden')) loadCompletionTab();
}, 60000);

window.onload = async function () {
    setDefaultDates();
    const me = await api('/api/me');
    if (me.loggedIn) openApp(me.username, me.role, me.dept);
};

function localToday() {
    const now = new Date();
    return now.getFullYear() + '-' + String(now.getMonth() + 1).padStart(2, '0')
        + '-' + String(now.getDate()).padStart(2, '0');
}

function setDefaultDates() {
    const today = localToday();
    const nextDate = new Date();
    nextDate.setDate(nextDate.getDate() + 1);
    const tomorrow = isoDate(nextDate);
    ['fDate', 'engineDate', 'planDate', 'savedPlanDate', 'dashDate'].forEach(id => {
        const input = byId(id);
        if (input && !input.value) input.value = tomorrow;
    });
    const requestDate = byId('fDate');
    if (requestDate) requestDate.min = today;
}

function getPlanningDate() {
    return byId('planDate')?.value
        || byId('engineDate')?.value || localToday();
}

function displayDate(isoDate) {
    if (!isoDate) return '';
    if (!/^\d{4}-\d{2}-\d{2}$/.test(String(isoDate))) return String(isoDate);
    const date = new Date(isoDate + 'T12:00:00');
    return date.toLocaleDateString(undefined, { weekday: 'long', year: 'numeric', month: 'short', day: 'numeric' });
}

function syncPlannerDate(sourceId) {
    const source = byId(sourceId);
    ['engineDate', 'planDate'].filter(id => id !== sourceId).forEach(id => {
        const other = byId(id);
        if (other && source) other.value = source.value;
    });
    ['conflictBox', 'schedBox', 'allocBox', 'planBox', 'approveBox'].forEach(id => {
        const box = byId(id);
        if (box) box.innerHTML = '';
    });
}

function showTab(name, button) {
    document.querySelectorAll('.page').forEach(p => p.classList.add('hidden'));
    byId(name).classList.remove('hidden');

    document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
    button.classList.add('active');

    if (name === 'dashboard') {
        loadDashboard();
        if (currentRole === 'CONTROL') loadControlWorkAlert();
    }
    if (name === 'completion') loadCompletionTab();
    if (name === 'reports') loadReports();
    if (name === 'plan') loadSavedPlans();
}

function escapeHtml(value) {
    return String(value ?? '').replace(/[&<>\"']/g, ch => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '\"': '&quot;', "'": '&#39;'
    }[ch]));
}

function sectionLabel(sectionId) {
    return String(sectionId ?? '');
}

function makeTable(rows, columns, titles) {
    if (!rows || rows.length === 0) return '<p class="note">Nothing to show yet.</p>';

    let html = '<table><tr>';
    titles.forEach(t => html += '<th>' + t + '</th>');
    html += '</tr>';

    rows.forEach(row => {
        html += '<tr>';
        columns.forEach(col => {
            let value = row[col];
            if (value === null || value === undefined) value = '-';

            if (col === 'sectionId' || col === 'section_id') value = sectionLabel(value);
            if (col === 'workDate' || col === 'work_date' || col === 'schedule_date') value = displayDate(value);

            const safeValue = escapeHtml(value);
            if (col === 'priority') {
                const allowedPriority = ['Critical', 'High', 'Medium', 'Low'].includes(String(value)) ? String(value) : 'Low';
                html += '<td class="p-' + allowedPriority + '">' + safeValue + '</td>';
            } else {
                html += '<td>' + safeValue + '</td>';
            }
        });
        html += '</tr>';
    });
