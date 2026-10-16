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
