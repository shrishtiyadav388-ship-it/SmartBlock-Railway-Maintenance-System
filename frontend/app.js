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

    return html + '</table>';
}

async function loadDashboard() {
    if (currentRole === 'DEPARTMENT') return loadMyRequests();

    const dateInput = byId('dashDate');
    const rangeSelect = byId('dashRange');
    const filters = byId('controlDashboardFilters');
    const pendingCard = byId('pendingCard');
    if (filters) filters.classList.remove('hidden');
    if (pendingCard) pendingCard.classList.remove('hidden');
    const conflictCard = byId('conflictCard');
    if (conflictCard) conflictCard.classList.remove('hidden');

    const referenceValue = dateInput?.value || localToday();
    const mode = rangeSelect?.value || 'day';
    const range = getDashboardRange(referenceValue, mode);
    const rangeLabel = byId('dashboardRangeLabel');
    if (rangeLabel) rangeLabel.textContent = 'Dates: ' + displayDate(range.from) + ' – ' + displayDate(range.to) + '. Status filters the table.';
    const dashboardCalendarPanel = byId('dashboardCalendarPanel');
    if (dashboardCalendarPanel && mode !== 'month') {
        dashboardCalendarPanel.classList.add('hidden');
        byId('dashboardCalendar').innerHTML = '';
    }

    const result = await api('/api/control/requests?from=' + encodeURIComponent(range.from)
        + '&to=' + encodeURIComponent(range.to));
    if (!result || result.ok === false || !Array.isArray(result.requests)) {
        byId('dashTitle').textContent = 'Could not load requests';
        byId('taskTable').innerHTML = '<p class="error">' + escapeHtml(result?.message || 'Could not load requests. Check MySQL and the app terminal.') + '</p>';
        ['cRequests', 'cApproved', 'cConflicts', 'cPending', 'cReschedule', 'cRejected', 'cCritical'].forEach(id => {
            const element = byId(id);
            if (element) element.textContent = '–';
        });
        return;
    }

    const requests = result.requests;
    const calendarPanel = byId('dashboardCalendarPanel');
    if (calendarPanel) {
        if (mode === 'month') {
            calendarPanel.classList.remove('hidden');
            const monthDate = new Date(range.from + 'T12:00:00');
            byId('calendarMonthLabel').textContent = monthDate.toLocaleDateString(undefined, { month: 'long', year: 'numeric' });
            byId('dashboardCalendar').innerHTML = makeDashboardCalendar(range.from, range.to, requests, referenceValue);
        } else {
            calendarPanel.classList.add('hidden');
            byId('dashboardCalendar').innerHTML = '';
        }
    }

    const approved = requests.filter(r => String(r.status).toUpperCase() === 'APPROVED').length;
    const pending = requests.filter(r => String(r.status).toUpperCase() === 'PENDING').length;
    const reschedule = requests.filter(r => String(r.status).toUpperCase() === 'RESCHEDULE_REQUIRED').length;
    const rejected = requests.filter(r => String(r.status).toUpperCase() === 'REJECTED').length;
    const conflictCount = requests.filter(r => isTrueFlag(r.hasConflict)).length;
    const critical = requests.filter(r => String(r.priority).toLowerCase() === 'critical').length;
    byId('cRequests').textContent = requests.length;
    byId('cApproved').textContent = approved;
    byId('cConflicts').textContent = conflictCount;
    byId('cConflictsLabel').textContent = 'Conflicts';
    byId('cPending').textContent = pending;
    byId('cReschedule').textContent = reschedule;
    byId('cRejected').textContent = rejected;
    byId('cCritical').textContent = critical;

    const status = byId('dashStatus')?.value || 'all';
    const filtered = requests.filter(r => {
        if (status === 'PENDING') return String(r.status).toUpperCase() === 'PENDING';
        if (status === 'APPROVED') return String(r.status).toUpperCase() === 'APPROVED';
        if (['RESCHEDULE_REQUIRED', 'REJECTED', 'RESCHEDULED', 'WITHDRAWN'].includes(status)) return String(r.status).toUpperCase() === status;
        if (status === 'CONFLICT') return isTrueFlag(r.hasConflict);
        if (status === 'CRITICAL') return String(r.priority).toLowerCase() === 'critical';
        return true;
    });
    byId('dashTitle').textContent = 'Requests';
    byId('dashboardTableNote').textContent = filtered.length + ' requests. Conflicts need review before approval.';
    byId('taskTable').innerHTML = makeControlRequestTable(filtered);
}

function getDashboardRange(referenceValue, mode) {
    const reference = new Date(referenceValue + 'T12:00:00');
    let from = new Date(reference);
    let to = new Date(reference);
    if (mode === 'week') {
        to.setDate(to.getDate() + 6);
    } else if (mode === 'month') {
        from = new Date(reference.getFullYear(), reference.getMonth(), 1, 12);
        to = new Date(reference.getFullYear(), reference.getMonth() + 1, 0, 12);
    }
    return { from: isoDate(from), to: isoDate(to) };
}

function makeDashboardCalendar(fromValue, toValue, requests, selectedDate) {
    const from = new Date(fromValue + 'T12:00:00');
    const to = new Date(toValue + 'T12:00:00');
    const byDate = new Map();
    requests.forEach(request => {
        const day = String(request.workDate || '').slice(0, 10);
        if (!day) return;
        if (!byDate.has(day)) byDate.set(day, { total: 0, pending: 0, approved: 0, reschedule: 0, rejected: 0, conflicts: 0 });
        const item = byDate.get(day);
        item.total++;
        const requestStatus = String(request.status).toUpperCase();
        if (requestStatus === 'APPROVED') item.approved++;
        else if (requestStatus === 'PENDING') item.pending++;
        else if (requestStatus === 'RESCHEDULE_REQUIRED') item.reschedule++;
        else if (requestStatus === 'REJECTED') item.rejected++;
        if (isTrueFlag(request.hasConflict)) item.conflicts++;
    });

    let html = '';
    for (let blank = 0; blank < from.getDay(); blank++) {
        html += '<div class="calendar-empty" aria-hidden="true"></div>';
    }
    for (let date = new Date(from); date <= to; date.setDate(date.getDate() + 1)) {
        const value = isoDate(date);
        const counts = byDate.get(value) || { total: 0, pending: 0, approved: 0, reschedule: 0, rejected: 0, conflicts: 0 };
        const classes = ['calendar-day'];
        if (value === selectedDate) classes.push('selected');
        if (counts.conflicts) classes.push('has-conflict');
        html += '<button type="button" class="' + classes.join(' ') + '" onclick="chooseCalendarDay(\'' + value + '\')" aria-label="Open ' + escapeHtml(displayDate(value)) + ', ' + counts.total + ' requests">'
            + '<span class="calendar-day-number">' + date.getDate() + '</span>'
            + '<span class="calendar-count">' + (counts.total ? counts.total + (counts.total === 1 ? ' request' : ' requests') : 'No requests') + '</span>'
            + (counts.pending ? '<span class="calendar-detail">' + counts.pending + ' pending</span>' : '')
            + (counts.approved ? '<span class="calendar-detail">' + counts.approved + ' approved</span>' : '')
            + (counts.reschedule ? '<span class="calendar-conflict">' + counts.reschedule + ' need rescheduling</span>' : '')
            + (counts.rejected ? '<span class="calendar-detail">' + counts.rejected + ' rejected</span>' : '')
            + (counts.conflicts ? '<span class="calendar-conflict">' + counts.conflicts + ' overlap warning(s)</span>' : '')
            + '</button>';
    }
    return html;
}

function chooseCalendarDay(dateValue) {
    const dateInput = byId('dashDate');
    const rangeSelect = byId('dashRange');
    const statusSelect = byId('dashStatus');
    if (dateInput) dateInput.value = dateValue;
    if (rangeSelect) rangeSelect.value = 'day';
    if (statusSelect) statusSelect.value = 'all';
    loadDashboard();
}

function setDashboardPeriod(period) {
    const dateInput = byId('dashDate');
    const rangeSelect = byId('dashRange');
    if (dateInput) dateInput.value = localToday();
    if (rangeSelect) rangeSelect.value = period === 'week' ? 'week' : 'day';
    loadDashboard();
}

function isTrueFlag(value) {
    return value === true || Number(value) === 1;
}

function requestStatusLabel(status) {
    const labels = {
        PENDING: 'Pending', APPROVED: 'Approved', REJECTED: 'Rejected',
        RESCHEDULE_REQUIRED: 'Needs new time',
        RESCHEDULED: 'Revised',
        WITHDRAWN: 'Withdrawn'
    };
    return labels[String(status || '').toUpperCase()] || String(status || 'Unknown');
}

function statusTagClass(status) {
    status = String(status || '').toUpperCase();
    if (status === 'APPROVED' || status === 'RESCHEDULED') return 'good';
    if (status === 'REJECTED' || status === 'WITHDRAWN') return 'bad';
    if (status === 'RESCHEDULE_REQUIRED') return 'bad';
    return 'pending';
}

function completionStatusLabel(status) {
    const labels = {
        NOT_STARTED: 'Scheduled', SCHEDULED: 'Scheduled', IN_PROGRESS: 'In progress',
        REPORT_MISSING: 'No report received', OVERDUE: 'No report received',
        NOT_COMPLETED: 'Not completed', NEEDS_FOLLOW_UP: 'Needs follow-up',
        REPORTED: 'Waiting for Control Room', AWAITING_VERIFICATION: 'Waiting for Control Room',
        COMPLETED: 'Verified complete'
    };
    return labels[String(status || '').toUpperCase()] || String(status || 'Scheduled');
}

function completionTagClass(status) {
    status = String(status || '').toUpperCase();
    if (status === 'COMPLETED') return 'good';
    if (['REPORT_MISSING', 'OVERDUE', 'NOT_COMPLETED', 'NEEDS_FOLLOW_UP', 'RETURNED'].includes(status)) return 'bad';
    return 'pending';
}

async function loadControlWorkAlert() {
    if (currentRole !== 'CONTROL') return;
    const banner = byId('workStatusAlert');
    if (!banner) return;

    const result = await api('/api/control/completion-status');
    if (!result || result.ok === false || !Array.isArray(result.tasks)) {
        banner.classList.remove('hidden', 'waiting');
        byId('workStatusAlertTitle').textContent = 'Work status could not refresh';
        byId('workStatusAlertText').textContent = result?.message || 'Check the app and MySQL connection.';
        return;
    }

    const tasks = result.tasks;
    const count = (...states) => tasks.filter(task => states.includes(String(task.completionStatus || '').toUpperCase())).length;
    const noReport = count('REPORT_MISSING', 'OVERDUE');
    const unfinished = count('NOT_COMPLETED');
    const followUp = count('NEEDS_FOLLOW_UP');
    const waiting = count('AWAITING_VERIFICATION', 'REPORTED');
    const needsAction = noReport + unfinished + followUp;

    if (needsAction === 0 && waiting === 0) {
        banner.classList.add('hidden');
        return;
    }

    banner.classList.remove('hidden');
    banner.classList.toggle('waiting', needsAction === 0);
    byId('workStatusAlertTitle').textContent = needsAction > 0 ? 'Work needs attention' : 'Completion reports need checking';
    const messages = [];
    if (noReport) messages.push(noReport + (noReport === 1 ? ' job has no completion report' : ' jobs have no completion report'));
    if (unfinished) messages.push(unfinished + (unfinished === 1 ? ' job was reported not completed' : ' jobs were reported not completed'));
    if (followUp) messages.push(followUp + (followUp === 1 ? ' job needs follow-up' : ' jobs need follow-up'));
    if (waiting) messages.push(waiting + (waiting === 1 ? ' report is waiting for verification' : ' reports are waiting for verification'));
    byId('workStatusAlertText').textContent = messages.join('. ') + '. The alert updates while SmartBlock is open.';
}

async function loadDepartmentStatusAlert() {
    if (currentRole !== 'DEPARTMENT') return;
    const banner = byId('workStatusAlert');
    if (!banner) return;
    const rows = await api('/api/my-requests');
    if (!Array.isArray(rows)) return;
    const requests = rows.filter(task => task.status_request_note && task.status_requested_at
        && String(task.status || '').toUpperCase() === 'APPROVED'
        && !['COMPLETED', 'REPORTED', 'AWAITING_VERIFICATION'].includes(String(task.completion_status || '').toUpperCase()));
    if (!requests.length) {
        banner.classList.add('hidden');
        banner.classList.remove('waiting');
        return;
    }
    banner.classList.remove('hidden');
    banner.classList.add('waiting');
    byId('workStatusAlertTitle').textContent = 'Control Room has requested a work update';
    byId('workStatusAlertText').textContent = 'Please open Work status and send the completion status and a short report for: '
        + requests.map(task => task.task_id).join(', ') + '.';
}

function openWorkStatus() {
    const tab = Array.from(document.querySelectorAll('.tab')).find(button =>
        (button.getAttribute('onclick') || '').includes("showTab('completion'")
    );
    if (tab) showTab('completion', tab);
}

function makeControlRequestTable(rows) {
    if (!rows.length) {
        return '<div class="empty-state"><strong>No requests found</strong>'
            + '<p>Try another date or choose All requests.</p></div>';
    }
    let html = '<table><thead><tr><th>Task</th><th>Work date</th><th>Requested window</th><th>Department / section</th>'
        + '<th>Work / priority</th><th>Approved slot</th><th>Request status</th><th>Actions</th></tr></thead><tbody>';
    rows.forEach(r => {
        const status = String(r.status || 'PENDING').toUpperCase();
        const conflict = isTrueFlag(r.hasConflict);
        const approvedSlot = r.approvedStart && r.approvedEnd
            ? escapeHtml(r.approvedStart + ' – ' + r.approvedEnd) : 'Not approved';
        let actions = '<span class="note">—</span>';
        const safeId = escapeHtml(r.taskId);
        if (status === 'PENDING') {
            actions = '<div class="decision-actions"><button class="btn-small" onclick="decideRequest(\'' + safeId + '\',\'RESCHEDULE_REQUIRED\')">Ask for new time</button>'
                + '<button class="btn-small btn-danger" onclick="decideRequest(\'' + safeId + '\',\'REJECTED\')">Reject</button></div>';
        }
        const reason = r.decisionReason ? '<div class="decision-reason">' + escapeHtml(r.decisionReason) + '</div>' : '';
        html += '<tr>'
            + '<td><strong>' + safeId + '</strong>' + (r.supersedesTaskId ? '<div class="note">Revises ' + escapeHtml(r.supersedesTaskId) + '</div>' : '') + '</td>'
            + '<td>' + escapeHtml(displayDate(r.workDate)) + '</td>'
            + '<td>' + escapeHtml((r.requestedStart || '-') + ' – ' + (r.requestedEnd || '-')) + '</td>'
            + '<td>' + escapeHtml(r.department || '-') + '<div class="note">' + escapeHtml((r.sectionName || r.sectionId || '-') + (r.sectionId ? ' (' + r.sectionId + ')' : '')) + '</div></td>'
            + '<td>' + escapeHtml(r.workDesc || '-') + '<div class="note">Priority: ' + escapeHtml(r.priority || '-') + '</div></td>'
            + '<td>' + approvedSlot + '</td>'
            + '<td><span class="tag ' + statusTagClass(status) + '">' + escapeHtml(requestStatusLabel(status)) + '</span>'
            + (conflict ? '<div><span class="tag bad">Conflict</span></div>' : '') + reason + '</td>'
            + '<td>' + actions + '</td>'
            + '</tr>';
    });
    return html + '</tbody></table>';
}

async function decideRequest(taskId, decision) {
    const actionLabel = decision === 'REJECTED' ? 'reject this request' : 'ask for a new time';
    if (!confirm('Confirm: ' + actionLabel + '?')) return;
    const reason = await askForText(decision === 'REJECTED'
        ? 'Why should this request be rejected? (5–500 characters)'
        : 'Why is a new time needed? (5–500 characters)');
    if (reason === null) return;
    if (reason.trim().length < 5 || reason.trim().length > 500) {
        alert('Enter a reason (5–500 characters).');
        return;
    }
    const result = await api('/api/control/requests/' + encodeURIComponent(taskId) + '/decision', 'POST', { decision, reason });
    alert(result.message || (result.ok ? 'Decision saved.' : 'Decision not saved.'));
    if (result.ok) await loadDashboard();
}

async function withdrawRequest(taskId) {
    if (!confirm('Withdraw ' + taskId + '? It will no longer be planned.')) return;
    const result = await api('/api/my-requests/' + encodeURIComponent(taskId) + '/withdraw', 'POST');
    alert(result.message || (result.ok ? 'Request withdrawn.' : 'Could not withdraw the request.'));
    if (result.ok) await loadMyRequests();
}

async function loadMyRequests() {
    const filters = byId('controlDashboardFilters');
    const conflictCard = byId('conflictCard');
    if (filters) filters.classList.add('hidden');
    if (conflictCard) conflictCard.classList.add('hidden');

    const rows = await api('/api/my-requests');
    if (!Array.isArray(rows)) {
        byId('taskTable').innerHTML = '<p class="error">' + escapeHtml(rows?.message || 'Could not load your requests.') + '</p>';
        return;
    }

    myRequestCache = rows;
    const approved = rows.filter(r => r.status === 'APPROVED').length;
    const pending = rows.filter(r => r.status === 'PENDING').length;
    const reschedule = rows.filter(r => r.status === 'RESCHEDULE_REQUIRED').length;
    const rejected = rows.filter(r => r.status === 'REJECTED').length;

    byId('cRequests').textContent = rows.length;
    byId('cApproved').textContent = approved;
    byId('cConflicts').textContent = reschedule;
    byId('cConflictsLabel').textContent = 'Needs new time';
    byId('cPending').textContent = pending;
    byId('cReschedule').textContent = reschedule;
    byId('cRejected').textContent = rejected;
    byId('cCritical').textContent = rows.filter(r => r.priority === 'Critical').length;

    byId('dashTitle').textContent = 'My requests';
    byId('dashboardTableNote').textContent = 'Check decisions here. Use Work status to report finished or delayed work.';

    let html = '<table><tr><th>Task</th><th>Date / work</th><th>Section</th><th>Priority</th><th>Request status</th><th>Approved time</th><th>Control note</th><th>Action</th></tr>';
    rows.forEach(r => {
        const status = String(r.status || 'PENDING').toUpperCase();
        const when = r.start_time ? (r.start_time + ' - ' + r.end_time) : '-';
        const priorityClass = ['Critical', 'High', 'Medium', 'Low'].includes(r.priority) ? r.priority : 'Low';
        let action = '<span class="note">—</span>';
        if (status === 'RESCHEDULE_REQUIRED') action = '<div class="decision-actions"><button class="btn-small" onclick="prepareRevision(\'' + escapeHtml(r.task_id) + '\')">Edit and resend</button>'
            + '<button class="btn-small btn-danger" onclick="withdrawRequest(\'' + escapeHtml(r.task_id) + '\')">Withdraw</button></div>';
        else if (status === 'PENDING') action = '<button class="btn-small btn-danger" onclick="withdrawRequest(\'' + escapeHtml(r.task_id) + '\')">Withdraw</button>';
        html += '<tr><td>' + escapeHtml(r.task_id)
              + (r.supersedes_task_id ? '<div class="note">Revises ' + escapeHtml(r.supersedes_task_id) + '</div>' : '')
              + '</td><td>' + escapeHtml(displayDate(r.work_date)) + '<div class="note">' + escapeHtml(r.work_desc) + '</div></td><td>' + escapeHtml(sectionLabel(r.section_id))
              + '</td><td class="p-' + priorityClass + '">' + escapeHtml(r.priority) + '</td><td><span class="tag ' + statusTagClass(status) + '">' + escapeHtml(requestStatusLabel(status)) + '</span></td>'
              + '<td>' + escapeHtml(when) + '</td><td>' + escapeHtml(r.decision_reason || 'No decision recorded yet') + '</td><td>' + action + '</td></tr>';
    });
    byId('taskTable').innerHTML = rows.length ? html + '</table>' : '<div class="empty-state"><strong>No requests yet</strong><p>Select New Request to add maintenance work.</p></div>';
}

async function prepareRevision(taskId) {
    const previous = myRequestCache.find(r => String(r.task_id) === String(taskId));
    if (!previous || String(previous.status).toUpperCase() !== 'RESCHEDULE_REQUIRED') {
        alert('This request has changed. Refresh the dashboard.');
        return;
    }
    const today = localToday();
    byId('fDate').value = previous.work_date < today ? today : previous.work_date;
    byId('fSection').value = previous.section_id;
    byId('fWork').value = previous.work_desc || '';
    byId('fPriority').value = previous.priority || 'Medium';
    byId('fDuration').value = previous.duration_min || 60;
    byId('fStart').value = previous.requested_start || '10:00';
    byId('fEnd').value = previous.requested_end || '11:00';
    byId('fRevisesTaskId').value = taskId;
    const notice = byId('revisionNotice');
    notice.textContent = 'Editing ' + taskId + '. Update the details and send it again.';
    notice.classList.remove('hidden');
    const tab = Array.from(document.querySelectorAll('.tab')).find(t => t.textContent.includes('New Request'));
    if (tab) showTab('request', tab);
    byId('requestMsg').textContent = '';
}

async function submitTask() {
    const button = byId('submitRequestButton');
    if (button && button.disabled) return;
    const form = {
        workDate: byId('fDate').value,
        sectionId: byId('fSection').value.trim().toUpperCase(),
        workDesc: byId('fWork').value.trim(),
        priority: byId('fPriority').value,
        durationMin: byId('fDuration').value,
        requestedStart: byId('fStart').value,
        requestedEnd: byId('fEnd').value,
        revisesTaskId: byId('fRevisesTaskId').value
    };

    const duration = Number(form.durationMin);
    const startMinutes = Number(String(form.requestedStart).slice(0, 2)) * 60 + Number(String(form.requestedStart).slice(3, 5));
    const endMinutes = Number(String(form.requestedEnd).slice(0, 2)) * 60 + Number(String(form.requestedEnd).slice(3, 5));
    const validSectionId = ['S1', 'S2', 'S3'].includes(form.sectionId);
    if (!form.workDate || !validSectionId || !form.workDesc || !form.requestedStart || !form.requestedEnd
            || endMinutes <= startMinutes || duration < 15 || duration > 720 || duration > endMinutes - startMinutes) {
        byId('requestMsg').textContent = 'Choose S1, S2 or S3, then check the date, work description, duration and time window.';
        return;
    }
