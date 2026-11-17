function byId(id) {
    return document.getElementById(id);
}

async function api(url, method, body) {
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), url.startsWith('/api/chatbot/') ? 45000 : 15000);
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
let chatHistory = [];

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
    ['fDate', 'engineDate', 'planDate', 'savedPlanDate', 'dashDate', 'timelineDate'].forEach(id => {
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
    if (name === 'timeline') loadTimeline();
}

function addChatBubble(role, text) {
    const box = byId('chatMessages');
    const bubble = document.createElement('div');
    bubble.className = 'chat-bubble ' + (role === 'user' ? 'user-bubble' : 'assistant-bubble');
    bubble.textContent = text;
    box.appendChild(bubble);
    box.scrollTop = box.scrollHeight;
    return bubble;
}

async function sendChatMessage(prefill) {
    const input = byId('chatInput');
    const message = String(prefill ?? input.value).trim();
    if (!message || byId('chatSendButton').disabled) return;

    input.value = '';
    addChatBubble('user', message);
    byId('chatSendButton').disabled = true;
    byId('chatStatus').textContent = 'Thinking...';

    const result = await api('/api/chatbot/message', 'POST', {
        message,
        history: chatHistory.slice(-8)
    });

    if (result.ok && result.reply) {
        chatHistory.push({ role: 'user', content: message });
        chatHistory.push({ role: 'assistant', content: result.reply });
        chatHistory = chatHistory.slice(-8);
        addChatBubble('assistant', result.reply);
        byId('chatStatus').textContent = result.mode === 'local'
            ? 'Offline assistant · no API key needed'
            : 'AI can make mistakes. Follow official railway procedures.';
    } else {
        addChatBubble('assistant', result.message || 'Could not get a reply. Please try again.');
        byId('chatStatus').textContent = 'Try again or ask a SmartBlock topic.';
    }
    byId('chatSendButton').disabled = false;
    input.focus();
}

function askChatSuggestion(question) {
    if (!byId('chatSendButton').disabled) sendChatMessage(question);
}

function clearChat() {
    chatHistory = [];
    byId('chatMessages').replaceChildren();
    addChatBubble('assistant', 'New chat started. What would you like to know?');
    byId('chatStatus').textContent = 'Offline assistant · no API key needed';
    byId('chatInput').focus();
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
        + '<th>Work / priority</th><th>Workers needed</th><th>Approved slot</th><th>Request status</th><th>Actions</th></tr></thead><tbody>';
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
            + '<td>' + escapeHtml(r.requiredWorkers || 1) + ' team(s)<div class="note">Preferred: ' + escapeHtml((r.requested_team || 'ANY') === 'ANY' ? 'Any available' : 'Team ' + r.requested_team) + '</div></td>'
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

    let html = '<table><tr><th>Task</th><th>Date / work</th><th>Section</th><th>Workers</th><th>Priority</th><th>Request status</th><th>Approved time</th><th>Control note</th><th>Action</th></tr>';
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
              + '</td><td>' + escapeHtml(r.required_workers || 1) + ' team(s)<div class="note">Preferred: ' + escapeHtml((r.requestedTeam || 'ANY') === 'ANY' ? 'Any available' : 'Team ' + r.requestedTeam) + '</div></td><td class="p-' + priorityClass + '">' + escapeHtml(r.priority) + '</td><td><span class="tag ' + statusTagClass(status) + '">' + escapeHtml(requestStatusLabel(status)) + '</span></td>'
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
    if (byId('fRequiredWorkers')) byId('fRequiredWorkers').value = String(previous.required_workers || 1);
    if (byId('fRequestedTeam')) byId('fRequestedTeam').value = String(previous.requested_team || 'ANY').toUpperCase();
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
        requiredWorkers: byId('fRequiredWorkers').value,
        requestedTeam: byId('fRequestedTeam').value,
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

    const oldText = button ? button.textContent : '';
    if (button) { button.disabled = true; button.textContent = 'Sending...'; button.setAttribute('aria-busy', 'true'); }
    byId('requestMsg').textContent = 'Saving request...';
    try {
        const result = await api('/api/tasks', 'POST', form);
        byId('requestMsg').textContent = result.ok
            ? result.message
            : (result.message || 'Request could not be saved.');
        if (result.ok) {
            byId('fWork').value = '';
            byId('fSection').value = '';
            byId('fRequiredWorkers').value = '1';
            byId('fRequestedTeam').value = 'ANY';
            byId('fRevisesTaskId').value = '';
            byId('revisionNotice').textContent = '';
            byId('revisionNotice').classList.add('hidden');
            if (currentRole === 'DEPARTMENT') await loadMyRequests();
        }
    } catch (error) {
        console.error('Request submission failed:', error);
        byId('requestMsg').textContent = 'Request failed. Check the application terminal and try again.';
    } finally {
        if (button && button.isConnected) {
            button.disabled = false;
            button.textContent = oldText;
            button.removeAttribute('aria-busy');
        }
    }
}

async function loadCompletionPanel() {
    if (currentRole !== 'CONTROL') return;
    const panel = byId('completionStatusPanel');
    if (panel) panel.classList.remove('hidden');
    const result = await api('/api/control/completion-status');
    const summary = byId('completionSummary');
    const table = byId('completionTable');
    if (!result || result.ok === false || !Array.isArray(result.tasks)) {
        summary.innerHTML = '';
        table.innerHTML = '<p class="error">' + escapeHtml(result?.message || 'Could not load work status. Check MySQL and the app terminal.') + '</p>';
        return;
    }
    const tasks = result.tasks;
    const count = (...states) => tasks.filter(task => states.includes(String(task.completionStatus || '').toUpperCase())).length;
    summary.innerHTML = [
        ['No report', count('REPORT_MISSING', 'OVERDUE'), 'red'],
        ['Needs follow-up', count('NOT_COMPLETED', 'NEEDS_FOLLOW_UP'), 'red'],
        ['Waiting for check', count('AWAITING_VERIFICATION', 'REPORTED'), 'amber'],
        ['Verified complete', count('COMPLETED'), 'green']
    ].map(item => '<div class="card ' + item[2] + '"><div class="num">' + item[1] + '</div><div class="lbl">' + item[0] + '</div></div>').join('');
    if (!tasks.length) {
        table.innerHTML = '<div class="empty-state"><strong>No approved work yet</strong><p>After a job is approved, its completion status appears here.</p></div>';
        return;
    }
    let html = '<table><thead><tr><th>Task</th><th>Date</th><th>Department</th><th>Work</th><th>Status</th><th>Update / Control note</th><th>Action</th></tr></thead><tbody>';
    tasks.forEach(task => {
        const state = String(task.completionStatus || '').toUpperCase();
        let action = '<span class="note">No action</span>';
        if (state === 'AWAITING_VERIFICATION') {
            action = '<div class="decision-actions"><button class="btn-small" data-completion-action="CONFIRM" data-task-id="' + escapeHtml(task.taskId) + '">Verify complete</button>'
                + '<button class="btn-small btn-danger" data-completion-action="RETURN" data-task-id="' + escapeHtml(task.taskId) + '">Return</button></div>';
        } else if (['REPORT_MISSING', 'OVERDUE'].includes(state)) {
            const requestLabel = task.statusRequestedAt ? 'Send reminder' : 'Request status';
            action = '<div class="decision-actions"><button class="btn-small" data-request-status-task="' + escapeHtml(task.taskId) + '">' + requestLabel + '</button>'
                + '<button class="btn-small btn-danger" data-reschedule-task="' + escapeHtml(task.taskId) + '">Request new time</button></div>';
        } else if (['NOT_COMPLETED', 'NEEDS_FOLLOW_UP'].includes(state)) {
            action = '<div class="decision-actions"><button class="btn-small" data-follow-up-task="' + escapeHtml(task.taskId) + '">Record follow-up</button>'
                + '<button class="btn-small btn-danger" data-reschedule-task="' + escapeHtml(task.taskId) + '">Request new time</button></div>';
        }
        let update = escapeHtml(task.reportedAt || '-');
        if (task.reportedBy) update += '<div class="note">Department: ' + escapeHtml(task.reportedBy) + '</div>';
        if (task.completionReportNote) update += '<div class="note">' + escapeHtml(task.completionReportNote) + '</div>';
        if (task.statusRequestNote) update += '<div class="note"><strong>Message sent: </strong>' + escapeHtml(task.statusRequestNote)
            + (task.statusRequestedAt ? ' (' + escapeHtml(task.statusRequestedAt) + ')' : '') + '</div>';
        if (task.completionNote) update += '<div class="note">Control Room note: ' + escapeHtml(task.completionNote) + '</div>';
        if (task.completedAt) update += '<div class="note">Verified ' + escapeHtml(task.completedAt) + (task.completedBy ? ' by ' + escapeHtml(task.completedBy) : '') + '</div>';
        html += '<tr><td><strong>' + escapeHtml(task.taskId) + '</strong></td>'
            + '<td>' + escapeHtml(displayDate(task.workDate)) + '</td>'
            + '<td>' + escapeHtml(task.department) + '</td>'
            + '<td>' + escapeHtml(task.workDesc || '-') + '</td>'
            + '<td><span class="tag ' + completionTagClass(state) + '">' + escapeHtml(completionStatusLabel(state)) + '</span></td>'
            + '<td>' + update + '</td><td>' + action + '</td></tr>';
    });
    table.innerHTML = html + '</tbody></table>';
}

async function loadCompletionTab() {
    const controlPanel = byId('completionStatusPanel');
    const departmentPanel = byId('departmentCompletionPanel');
    if (currentRole === 'CONTROL') {
        if (controlPanel) controlPanel.classList.remove('hidden');
        if (departmentPanel) departmentPanel.classList.add('hidden');
        await loadCompletionPanel();
    } else if (currentRole === 'DEPARTMENT') {
        if (controlPanel) controlPanel.classList.add('hidden');
        if (departmentPanel) departmentPanel.classList.remove('hidden');
        await loadDepartmentCompletion();
    }
}

async function loadDepartmentCompletion() {
    if (currentRole !== 'DEPARTMENT') return;
    const table = byId('departmentCompletionTable');
    const result = await api('/api/my-requests');
    if (!Array.isArray(result)) {
        table.innerHTML = '<p class="error">' + escapeHtml(result?.message || 'Could not load work status. Check the app terminal.') + '</p>';
        return;
    }

    const tasks = result.filter(task => String(task.status || '').toUpperCase() === 'APPROVED');
    if (!tasks.length) {
        table.innerHTML = '<div class="empty-state"><strong>No approved work yet</strong><p>Approved work will appear here.</p></div>';
        return;
    }

    let html = '<table><thead><tr><th>Task / work</th><th>Date</th><th>Approved time</th><th>Status</th><th>Your update</th><th>Action</th></tr></thead><tbody>';
    tasks.forEach(task => {
        const taskId = String(task.task_id || '');
        const state = String(task.completion_status || 'SCHEDULED').toUpperCase();
        const workDate = String(task.schedule_date || task.work_date || '').slice(0, 10);
        const start = String(task.start_time || '').slice(0, 5);
        const end = String(task.end_time || '').slice(0, 5);
        const hasBlock = Boolean(task.schedule_date && start && end);
        // These flags come from MySQL, so the page and server use the same clock.
        const slotHasStarted = hasBlock && Number(task.work_started) === 1;
        const slotHasEnded = hasBlock && Number(task.work_ended) === 1;
        let action = hasBlock
            ? '<span class="note">Status buttons open at ' + escapeHtml(start || 'the approved start time') + '.</span>'
            : '<span class="note">Waiting for Control Room to approve a time slot.</span>';
        if (hasBlock && slotHasStarted && !['COMPLETED', 'AWAITING_VERIFICATION', 'REPORTED'].includes(state)) {
            action = '<div class="decision-actions">'
                + '<button type="button" class="btn-small" data-dept-work-action="COMPLETED" data-task-id="' + escapeHtml(taskId) + '">Report completed</button>'
                + '<button type="button" class="btn-small btn-danger" data-dept-work-action="NOT_COMPLETED" data-task-id="' + escapeHtml(taskId) + '">' + (slotHasEnded ? 'Report not completed' : 'Report delay') + '</button>'
                + '</div>';
        } else if (state === 'COMPLETED') {
            action = '<span class="tag good">Verified</span>';
        } else if (['AWAITING_VERIFICATION', 'REPORTED'].includes(state)) {
            action = '<span class="tag pending">Awaiting Control Room</span>';
        }
        html += '<tr><td><strong>' + escapeHtml(taskId) + '</strong><div class="note">' + escapeHtml(task.work_desc || '-') + '</div></td>'
            + '<td>' + escapeHtml(displayDate(workDate)) + '</td>'
            + '<td>' + escapeHtml(start && end ? start + ' – ' + end : 'Not assigned') + '</td>'
            + '<td><span class="tag ' + completionTagClass(state) + '">' + escapeHtml(completionStatusLabel(state)) + '</span></td>'
            + '<td>' + escapeHtml(task.completion_reported_at || '-')
            + (task.completion_report_note ? '<div class="note">' + escapeHtml(task.completion_report_note) + '</div>' : '')
            + (task.status_request_note ? '<div class="note"><strong>Control Room message (' + escapeHtml(task.status_requested_at || '') + '): </strong>' + escapeHtml(task.status_request_note) + '</div>' : '')
            + (task.completion_note ? '<div class="note">Control Room note: ' + escapeHtml(task.completion_note) + '</div>' : '') + '</td>'
            + '<td>' + action + '</td></tr>';
    });
    table.innerHTML = html + '</tbody></table>';
}

async function refreshControlViews() {
    if (byId('completion') && !byId('completion').classList.contains('hidden')) await loadCompletionTab();
    if (byId('dashboard') && !byId('dashboard').classList.contains('hidden')) await loadDashboard();
    await loadControlWorkAlert();
}

async function requestNewSlot(taskId, button) {
    const reason = await askForText('Why is a new time needed? (5–500 characters)');
    if (reason === null) return;
    if (reason.trim().length < 5 || reason.trim().length > 500) {
        alert('Enter a reason (5–500 characters).');
        return;
    }
    if (!confirm('Ask for a new time for ' + taskId + '? The old approval stays in history.')) return;

    const originalText = button ? button.textContent : '';
    if (button) { button.disabled = true; button.textContent = 'Sending...'; button.setAttribute('aria-busy', 'true'); }
    try {
        const result = await api('/api/control/tasks/' + encodeURIComponent(taskId) + '/reschedule', 'POST', { reason: reason.trim() });
        alert(result.message || (result.ok ? 'New slot requested.' : 'Could not request a new time.'));
        if (result.ok) await refreshControlViews();
    } finally {
        if (button && button.isConnected) { button.disabled = false; button.textContent = originalText; button.removeAttribute('aria-busy'); }
    }
}

async function markTaskCompleted(taskId, action, button) {
    if (button && button.disabled) return;

    // Ask for the report before showing the button as busy.
    const completed = action === 'COMPLETED';
    const note = await askForText(completed
        ? 'What work was done? (5–500 characters)'
        : 'Why was the work not finished? (5–500 characters)');
    if (note === null) return;
    if (note.trim().length < 5 || note.trim().length > 500) {
        alert('Enter a note (5–500 characters).');
        return;
    }
    const originalText = button ? button.textContent : '';
    if (button) {
        button.disabled = true;
        button.textContent = 'Sending...';
        button.setAttribute('aria-busy', 'true');
    }

    try {
        const result = await api('/api/my-requests/' + encodeURIComponent(taskId) + '/complete', 'POST', { action, note: note.trim() });
        alert(result.message || (result.ok ? 'Work status sent.' : 'Work status was not sent.'));
        if (result.ok) {
            await loadDepartmentCompletion();
            if (byId('dashboard') && !byId('dashboard').classList.contains('hidden')) await loadMyRequests();
        }
    } catch (error) {
        alert('Could not send the work status. Check the app terminal and try again.');
    } finally {
        // The table refresh may remove this button; only restore it if still present.
        if (button && button.isConnected) {
            button.disabled = false;
            button.textContent = originalText;
            button.removeAttribute('aria-busy');
        }
    }
}

async function requestDepartmentStatus(taskId, button) {
    const defaultMessage = 'Please send the completion status and a short work report for task ' + taskId + ' in SmartBlock.';
    const note = await askForText('Message to the department:', defaultMessage);
    if (note === null) return;
    if (note.trim().length < 5 || note.trim().length > 500) {
        alert('Enter a message between 5 and 500 characters.');
        return;
    }
    const originalText = button ? button.textContent : '';
    if (button) { button.disabled = true; button.textContent = 'Sending...'; button.setAttribute('aria-busy', 'true'); }
    try {
        const result = await api('/api/control/tasks/' + encodeURIComponent(taskId) + '/request-status', 'POST', { note: note.trim() });
        alert(result.message || (result.ok ? 'Status request sent.' : 'Could not send status request.'));
        if (result.ok) await refreshControlViews();
    } finally {
        if (button && button.isConnected) { button.disabled = false; button.textContent = originalText; button.removeAttribute('aria-busy'); }
    }
}

async function recordControlFollowUp(taskId, button) {
    const note = await askForText('What did you do to follow up? (5–500 characters)');
    if (note === null) return;
    if (note.trim().length < 5 || note.trim().length > 500) {
        alert('Enter a follow-up note (5–500 characters).');
        return;
    }
    const originalText = button ? button.textContent : '';
    if (button) { button.disabled = true; button.textContent = 'Saving...'; button.setAttribute('aria-busy', 'true'); }
    try {
        const result = await api('/api/control/tasks/' + encodeURIComponent(taskId) + '/follow-up', 'POST', { note: note.trim() });
        alert(result.message || (result.ok ? 'Follow-up saved.' : 'Could not save the follow-up.'));
        if (result.ok) await refreshControlViews();
    } finally {
        if (button && button.isConnected) { button.disabled = false; button.textContent = originalText; button.removeAttribute('aria-busy'); }
    }
}

async function verifyTaskCompletion(taskId, action, button) {
    let note = '';
    if (action === 'RETURN') {
        note = await askForText('Why are you returning this report? (5–500 characters)');
        if (note === null) return;
        if (note.trim().length < 5 || note.trim().length > 500) {
            alert('Enter a return reason (5–500 characters).');
            return;
        }
    } else {
        note = await askForText('Optional note (press OK to skip):') || '';
        if (note.trim().length > 500) {
            alert('Keep the note under 500 characters.');
            return;
        }
    }
    if (!confirm(action === 'CONFIRM'
        ? 'Mark ' + taskId + ' as verified complete?'
        : 'Return ' + taskId + ' to the department for follow-up?')) return;

    const originalText = button ? button.textContent : '';
    if (button) { button.disabled = true; button.textContent = 'Saving...'; button.setAttribute('aria-busy', 'true'); }
    try {
        const result = await api('/api/control/tasks/' + encodeURIComponent(taskId) + '/completion', 'POST', { action, note: note.trim() });
        alert(result.message || (result.ok ? 'Saved.' : 'Could not save completion decision.'));
        if (result.ok) await refreshControlViews();
    } finally {
        if (button && button.isConnected) { button.disabled = false; button.textContent = originalText; button.removeAttribute('aria-busy'); }
    }
}

async function loadConflicts() {
    const date = getPlanningDate();
    const conflicts = await api('/api/conflicts?date=' + encodeURIComponent(date));
    const box = byId('conflictBox');
    if (!Array.isArray(conflicts)) {
        box.innerHTML = '<p class="error">' + escapeHtml(conflicts?.message || 'Could not check conflicts. Check the app and database.') + '</p>';
        return;
    }

    if (conflicts.length === 0) {
        box.innerHTML = '<p class="note">Date: ' + date + '</p><p><span class="tag good">No conflicts on this date</span></p>';
        return;
    }

    let html = '<p class="note">Conflicts on ' + displayDate(date) + '</p><table><tr><th>Section</th><th>Request A</th><th>Request B</th><th>Result</th></tr>';
    conflicts.forEach(c => {
        html += '<tr><td>' + sectionLabel(c.sectionId) + '</td><td>' + c.taskA + '</td><td>' + c.taskB
              + '</td><td><span class="tag bad">overlap</span></td></tr>';
    });
    box.innerHTML = html + '</table>';
}

async function loadScheduling() {
    const date = getPlanningDate();
    const data = await api('/api/scheduling?date=' + encodeURIComponent(date));
    if (!data || !Array.isArray(data.fcfs) || !Array.isArray(data.sjf)
            || !Array.isArray(data.priority) || !Number.isFinite(Number(data.fcfsAverage))) {
        byId('schedBox').innerHTML = '<p class="error">' + escapeHtml(data?.message || 'Could not compare schedules. Check the app and database.') + '</p>';
        return;
    }

    const columns = ['taskId', 'department', 'sectionId', 'priority', 'start', 'end', 'waitingMin'];
    const titles = ['Task', 'Department', 'Section', 'Priority', 'Start', 'End', 'Waited (min)'];

    let html = '<p class="note">Requests for ' + displayDate(date) + ' only.</p>';
    html += '<div class="sub-head">FCFS — first come, first served</div>';
    html += makeTable(data.fcfs, columns, titles);
    html += '<p class="note">Average waiting time: ' + data.fcfsAverage.toFixed(1) + ' min</p>';

    html += '<div class="sub-head">SJF — shortest job first</div>';
    html += makeTable(data.sjf, columns, titles);
    html += '<p class="note">Average waiting time: ' + data.sjfAverage.toFixed(1) + ' min</p>';

    html += '<div class="sub-head">Priority — highest priority first</div>';
    html += makeTable(data.priority, columns, titles);
    html += '<p class="note">Average waiting time: ' + data.priorityAverage.toFixed(1) + ' min</p>';
    html += '<p class="note">While building a day’s plan, jobs waiting for a suitable slot can gain priority. “Ask for a new time” is a separate action when a request cannot fit its requested window.</p>';

    byId('schedBox').innerHTML = html;
}

async function loadAllocation() {
    const rows = await api('/api/allocation?date=' + encodeURIComponent(getPlanningDate()));
    if (!Array.isArray(rows)) {
        byId('allocBox').innerHTML = '<p class="error">' + escapeHtml(rows?.message || 'Could not check teams. Please try again.') + '</p>';
        return;
    }
    byId('allocBox').innerHTML = makeTable(
        rows, ['taskId', 'sectionId', 'status', 'start', 'end'], ['Task', 'Section', 'Status', 'Start', 'End']);
}

async function loadPlan() {
    const date = getPlanningDate();
    const data = await api('/api/plan?date=' + encodeURIComponent(date));
    if (!data || !Array.isArray(data.plan)) {
        byId('planBox').innerHTML = '<p class="error">' + escapeHtml(data?.message || 'Could not build the plan. Please try again.') + '</p>';
        return;
    }

    const plan = data.plan;
    const safetyByTask = new Map((data.resourceSafety || []).map(item => [String(item.taskId), item]));
    const scheduled = plan.filter(item => item.scheduleStatus === 'Scheduled' || item.scheduled === true).length;
    const unscheduled = plan.length - scheduled;
    let html = '<p class="note"><strong>Plan date:</strong> ' + displayDate(date) + '</p>';
    html += '<div class="saved-summary plan-summary">'
        + '<div class="mini-stat"><strong>' + plan.length + '</strong><span>Requests</span></div>'
        + '<div class="mini-stat"><strong>' + scheduled + '</strong><span>Time found</span></div>'
        + '<div class="mini-stat"><strong>' + unscheduled + '</strong><span>Need new time</span></div>'
        + '</div>';

    if (!plan.length) {
        html += '<div class="empty-state"><strong>No requests for this date</strong><p>Choose another date or wait for a department request.</p></div>';
    } else {
        html += '<div class="sub-head">Proposed schedule</div><table class="plan-table"><thead><tr><th>Task / work</th><th>Department</th><th>Section</th><th>Priority</th><th>Planned time</th><th>Status</th><th>Team check</th><th>Next step</th></tr></thead><tbody>';
        plan.forEach(item => {
            const safety = safetyByTask.get(String(item.taskId));
            const isScheduled = item.scheduleStatus === 'Scheduled' || item.scheduled === true;
            const safe = Boolean(safety && safety.safe);
            const status = isScheduled ? 'Time found' : 'No suitable time';
            const teamCheck = !isScheduled ? 'Not checked' : ((safety?.availableWorkers ?? 0) + ' available / ' + (safety?.neededWorkers ?? 0) + ' needed');
            const nextStep = isScheduled && safe ? 'Ready to approve' : 'Review request';
            html += '<tr><td><strong>' + escapeHtml(item.taskId) + '</strong><div class="decision-reason">' + escapeHtml(item.workDesc || 'Maintenance work') + '</div></td>'
                + '<td>' + escapeHtml(item.department) + '</td>'
                + '<td>' + escapeHtml(item.sectionId) + '</td>'
                + '<td class="p-' + escapeHtml(['Critical','High','Medium','Low'].includes(String(item.priority)) ? item.priority : 'Low') + '">' + escapeHtml(item.priority) + '</td>'
                + '<td>' + escapeHtml(isScheduled ? (item.start + '–' + item.end) : '—') + '</td>'
                + '<td><span class="tag ' + (isScheduled ? 'good' : 'pending') + '">' + escapeHtml(status) + '</span></td>'
                + '<td><span class="tag ' + (safe ? 'good' : 'pending') + '">' + escapeHtml(isScheduled ? (safe ? 'Available' : 'Unavailable') : teamCheck) + '</span>'
                + (isScheduled ? '<div class="decision-reason">' + escapeHtml(teamCheck) + '</div>' : '')
                + (safety && !safe && safety.reason ? '<div class="decision-reason">' + escapeHtml(safety.reason) + '</div>' : '') + '</td>'
                + '<td>' + escapeHtml(nextStep) + '</td></tr>';
        });
        html += '</tbody></table>';
    }
    html += '<p class="ok-msg">' + escapeHtml(data.verification || 'Schedule checked.') + '</p>';
    html += '<p class="note">Team names are confirmed after approval and shown under Approved plans.</p>';
    byId('planBox').innerHTML = html;
}

async function approvePlan() {
    const date = getPlanningDate();
    const data = await api('/api/plan/approve?date=' + encodeURIComponent(date), 'POST');
    if (!data || !Array.isArray(data.saved) || !Array.isArray(data.failed)) {
        byId('approveBox').innerHTML = '<p class="error">' + escapeHtml(data?.message || 'Could not save the plan. Please try again.') + '</p>';
        return;
    }

    let html = '<div class="sub-head">Approval result — ' + displayDate(data.date || date) + '</div>';
    html += '<div class="saved-summary">'
        + '<div class="mini-stat"><strong>' + data.saved.length + '</strong><span>Approved</span></div>'
        + '<div class="mini-stat"><strong>' + data.failed.length + '</strong><span>Not approved</span></div></div>';
    if (data.saved.length) {
        html += '<div class="sub-head">Approved blocks and assigned teams</div><ul class="result-list">';
        data.saved.forEach(line => html += '<li>' + escapeHtml(line) + '</li>');
        html += '</ul>';
    }
    if (data.failed.length) {
        html += '<div class="sub-head">Needs review</div><ul class="result-list">';
        data.failed.forEach(line => html += '<li>' + escapeHtml(line) + '</li>');
        html += '</ul>';
    }
    byId('approveBox').innerHTML = html;
    loadSavedPlans();
    loadDashboard();
}

function isoDate(date) {
    return date.getFullYear() + '-' + String(date.getMonth() + 1).padStart(2, '0') + '-' + String(date.getDate()).padStart(2, '0');
}

async function loadSavedPlans() {
    const refValue = byId('savedPlanDate')?.value || localToday();
    const mode = byId('savedPlanRange')?.value || 'day';
    const ref = new Date(refValue + 'T12:00:00');
    const range = getDashboardRange(refValue, mode);
    let periodLabel = displayDate(refValue);
    if (mode === 'week') periodLabel = displayDate(range.from) + ' – ' + displayDate(range.to);
    if (mode === 'month') periodLabel = ref.toLocaleDateString(undefined, { month: 'long', year: 'numeric' });

    const result = await api('/api/reports/blocks-range?from=' + encodeURIComponent(range.from)
        + '&to=' + encodeURIComponent(range.to));
    const summary = byId('savedPlanSummary');
    const box = byId('savedPlanBox');
    if (!result || result.ok === false) {
        summary.textContent = result?.message || 'Could not load saved plans. Check the app and database.';
        box.innerHTML = '';
        return;
    }
    const blocks = result.blocks || [];
    const uniqueDays = new Set(blocks.map(b => String(b.schedule_date).slice(0, 10))).size;
    summary.innerHTML = '<div class="mini-stat"><strong>' + blocks.length + '</strong><span>approved blocks</span></div>'
        + '<div class="mini-stat"><strong>' + uniqueDays + '</strong><span>days with approvals</span></div>'
        + '<div class="mini-stat"><strong>' + escapeHtml(periodLabel) + '</strong><span>selected period</span></div>';
    if (!blocks.length) {
        box.innerHTML = '<div class="empty-state"><strong>No saved plans for this period</strong><p>Create and approve a plan, then search again.</p></div>';
        return;
    }
    box.innerHTML = makeTable(blocks,
        ['schedule_date', 'start_time', 'end_time', 'section_id', 'task_id', 'work_desc', 'dept_name', 'priority', 'assigned_workers'],
        ['Date', 'Start', 'End', 'Section', 'Task', 'Work', 'Department', 'Priority', 'Assigned team']);
}

async function loadReports() {
    const blocks = await api('/api/reports/blocks');
    const departments = await api('/api/reports/departments');
    const audit = await api('/api/reports/audit');
    const allReports = [blocks, departments, audit];
    if (!allReports.every(Array.isArray)) {
        const failed = allReports.find(item => !Array.isArray(item));
        byId('reportBox').innerHTML = '<p class="error">' + escapeHtml(failed?.message || 'Could not load reports. Check MySQL and the app terminal.') + '</p>';
        return;
    }

    let html = '<div class="sub-head">Approved blocks</div>';
    html += makeTable(blocks,
        ['schedule_date', 'section_id', 'task_id', 'dept_name', 'priority', 'start_time', 'end_time', 'assigned_workers'],
        ['Date', 'Section', 'Task', 'Department', 'Priority', 'Start', 'End', 'Assigned team']);

    html += '<div class="sub-head">Work by department</div>';
    html += makeTable(departments, ['dept_name', 'total_tasks', 'total_minutes'],
        ['Department', 'Tasks', 'Total minutes']);

    html += '<div class="sub-head">Approval history</div>';
    html += makeTable(audit, ['log_id', 'block_id', 'action', 'performed_by'],
        ['#', 'Block', 'Action', 'By']);

    byId('reportBox').innerHTML = html;
}


// Keep dynamic work-status buttons connected after each table refresh.
document.addEventListener('click', async event => {
    const button = event.target.closest('button');
    if (!button || button.disabled) return;
    const taskId = button.dataset.taskId;
    try {
        if (button.dataset.deptWorkAction && taskId) {
            await markTaskCompleted(taskId, button.dataset.deptWorkAction, button);
        } else if (button.dataset.completionAction && taskId) {
            await verifyTaskCompletion(taskId, button.dataset.completionAction, button);
        } else if (button.dataset.requestStatusTask) {
            await requestDepartmentStatus(button.dataset.requestStatusTask, button);
        } else if (button.dataset.followUpTask) {
            await recordControlFollowUp(button.dataset.followUpTask, button);
        } else if (button.dataset.rescheduleTask) {
            await requestNewSlot(button.dataset.rescheduleTask, button);
        }
    } catch (error) {
        console.error('SmartBlock button action failed:', error);
        alert('The action could not be completed. ' + (error?.message || 'Check the application terminal and try again.'));
    }
});


/* Check actual pending department requests, using their submitted section/time and worker needs. */
async function checkRealDepartmentRequests() {
    const box = byId('workerDeadlockResult');
    if (!box) return;
    const dateInput = byId('planDate');
    const date = dateInput?.value || new Date().toISOString().slice(0, 10);
    box.textContent = 'Checking real pending department requests for ' + date + '…';
    const result = await api('/api/control/deadlock/requests?date=' + encodeURIComponent(date), 'GET');
    if (!result || result.ok === false || !Array.isArray(result.requests)) {
        box.textContent = result?.message || 'Could not check pending requests. Check MySQL and the application terminal.';
        return;
    }
    const lines = [
        '<p><strong>Work date:</strong> ' + escapeHtml(result.date || date) + '</p>',
        '<p><strong>Real pending requests checked:</strong> ' + Number(result.requestsChecked || 0) +
            ' &nbsp; <strong>Unsafe resource claims:</strong> ' + Number(result.unsafeRequests || 0) + '</p>',
        '<p><strong>Result:</strong> ' + escapeHtml(result.interpretation || '') + '</p>'
    ];
    if (!result.requests.length) {
        lines.push('<p>No pending department requests were found for this date. Choose the date on which a department actually submitted a pending request.</p>');
    }
    for (const request of result.requests) {
        const safe = request.safe === true;
        const status = safe ? '<span class="tag good">Section &amp; worker check passed</span>' : '<span class="tag pending">Section/worker risk</span>';
        lines.push('<div class="deadlock-request-row"><p><strong>' + escapeHtml(request.taskId || '-') + ' — ' + escapeHtml(request.department || 'Department') + '</strong> ' + status + '</p>' +
            '<p><strong>Work:</strong> ' + escapeHtml(request.workDesc || '-') + '</p>' +
            '<p><strong>Section:</strong> ' + escapeHtml(request.sectionId || '-') + ' &nbsp; <strong>Requested time:</strong> ' + escapeHtml(request.requestedStart || '-') + '–' + escapeHtml(request.requestedEnd || '-') + '</p>' +
            '<p><strong>Section resource:</strong> ' + escapeHtml(request.availableSections ?? '-') + ' available &nbsp; <strong>Workers needed:</strong> ' + escapeHtml(request.requiredWorkers ?? '-') + ' &nbsp; <strong>Qualified workers available:</strong> ' + escapeHtml(request.availableWorkers ?? '-') + '</p>' +
            '<p>' + escapeHtml(request.reason || 'No further details.') + '</p></div>');
    }
    lines.push('<p><strong>Scope:</strong> this check considers only section and qualified-worker resources. A conflict means the proposed allocation is unsafe and should be reviewed before approval; pending requests do not hold resources yet, so this is a potential deadlock/resource risk, not proof of an active OS deadlock.</p>');
    box.innerHTML = lines.join('');
}

/* Inspect the actual proposed plan without approving it. */
async function runApprovalRiskPreview() {
    const box = byId('workerDeadlockResult');
    if (!box) return;
    const dateInput = byId('planDate');
    const date = dateInput?.value || new Date().toISOString().slice(0, 10);
    box.textContent = 'Checking the proposed plan for ' + date + '…';
    const result = await api('/api/plan?date=' + encodeURIComponent(date), 'GET');
    if (!result || result.ok === false || !Array.isArray(result.plan)) {
        box.textContent = result?.message || 'Could not load the proposed plan. Check MySQL and the app terminal.';
        return;
    }

    const safetyRows = Array.isArray(result.resourceSafety) ? result.resourceSafety : [];
    const safetyByTask = new Map(safetyRows.map(row => [String(row.taskId), row]));
    const unsafe = safetyRows.filter(row => row.safe === false);
    const scheduled = result.plan.filter(slot => slot.scheduled !== false);
    const heading = unsafe.length
        ? '<span class="tag pending">Approval resource risk found</span>'
        : '<span class="tag good">No resource risk found by this check</span>';
    const lines = [
        '<p><strong>What-if date:</strong> ' + escapeHtml(date) + '</p>',
        '<p><strong>Result:</strong> ' + heading + '</p>',
        '<p><strong>Requests in plan:</strong> ' + result.plan.length + '; <strong>scheduled:</strong> ' + scheduled.length + '</p>'
    ];

    for (const slot of result.plan) {
        const row = safetyByTask.get(String(slot.taskId));
        const state = slot.scheduled === false ? 'No time slot found' : row?.safe === false ? 'Resource risk' : row?.safe === true ? 'Resource check passed' : 'Check details';
        const detail = slot.scheduled === false ? (slot.reason || 'No suitable time slot found.') : (row?.reason || slot.reason || 'No additional details.');
        lines.push('<p><strong>' + escapeHtml(slot.taskId) + ' — ' + escapeHtml(slot.department || 'Department') + '</strong>: Section ' + escapeHtml(slot.sectionId || '-') + ', ' + escapeHtml(slot.start || '-') + '–' + escapeHtml(slot.end || '-') + ' — ' + escapeHtml(state) + '. ' + escapeHtml(detail) + '</p>');
    }

    if (unsafe.length) {
        lines.push('<p><strong>Control Room action:</strong> Do not ignore these warnings. Review or reschedule the affected request before approval.</p>');
    } else {
        lines.push('<p><strong>Control Room action:</strong> The proposed plan passes this section/worker resource check. Approval still rechecks resources inside the database transaction.</p>');
    }
    lines.push('<p><strong>OS concept note:</strong> A section overlap or unavailable worker is a resource conflict/unsafe allocation, not automatically a true deadlock. A deadlock requires a circular wait. This what-if check does not approve requests or change the database.</p>');
    box.innerHTML = lines.join('');
}

/* Check the live MySQL wait-for graph. */
async function runWorkerDeadlockTest() {
    const box = byId('workerDeadlockResult');
    if (!box) return;
    box.textContent = 'Checking current database waits…';
    const result = await api('/api/control/deadlock/run', 'POST');
    if (!result || result.ok === false) {
        box.textContent = result?.message || 'Could not check deadlocks. Check MySQL and the app terminal.';
        return;
    }

    const status = result.deadlockFound
        ? '<span class="tag pending">Deadlock found</span>'
        : '<span class="tag good">No deadlock detected</span>';
    const lines = [
        '<p><strong>Result:</strong> ' + status + '</p>',
        '<p><strong>Requests checked:</strong> ' + Number(result.requestsChecked || 0) + '</p>',
        '<p><strong>Active lock waits:</strong> ' + Number(result.activeLockWaits || 0) + '</p>',
        '<p>' + escapeHtml(result.explanation || '') + '</p>'
    ];
    if (Array.isArray(result.cycle) && result.cycle.length) {
        lines.push('<p><strong>Wait cycle:</strong> ' + result.cycle.map(escapeHtml).join(' → ') + '</p>');
    }
    box.innerHTML = lines.join('');
}


/* Deliberately reproduce a real MySQL deadlock using two worker rows, then roll back. */
async function runWorkerDeadlockDemo() {
    const box = byId('workerDeadlockResult');
    if (!box) return;
    box.textContent = 'Starting two test transactions…';
    const result = await api('/api/control/deadlock/demo', 'POST');
    if (!result || result.ok === false) {
        box.textContent = result?.message || 'Could not run the demo. Check MySQL and the app terminal.';
        return;
    }
    const heading = result.deadlockReproduced ? 'Deadlock reproduced' : 'Deadlock not reproduced';
    const colorClass = result.deadlockReproduced ? 'pending' : 'good';
    const lines = [
        '<p><strong>Result:</strong> <span class="tag ' + colorClass + '">' + heading + '</span></p>',
        '<p><strong>Worker rows used:</strong> ' + Number(result.workerOne) + ' and ' + Number(result.workerTwo) + '</p>',
        '<p><strong>Transaction A:</strong> ' + escapeHtml(result.transactionA || '') + '</p>',
        '<p><strong>Transaction B:</strong> ' + escapeHtml(result.transactionB || '') + '</p>',
        '<p>' + escapeHtml(result.message || '') + '</p>'
    ];
    box.innerHTML = lines.join('');
}

/* ---------- Timeline planner (Control Room) ---------- */
let timelineData = null;

function tlMinutes(value) {
    const m = /^(\d{1,2}):(\d{2})$/.exec(String(value || ''));
    return m ? Number(m[1]) * 60 + Number(m[2]) : null;
}

function tlPercent(minutes) {
    return Math.max(0, Math.min(100, minutes / 1440 * 100));
}

function tlSetMessage(text, isError) {
    const box = byId('timelineMsg');
    if (!box) return;
    box.textContent = text || '';
    box.style.color = isError ? '#ff8d94' : '';
}

async function loadTimeline() {
    if (currentRole !== 'CONTROL') return;
    const date = byId('timelineDate')?.value || localToday();
    tlSetMessage('');

    const data = await api('/api/plan/timeline?date=' + encodeURIComponent(date));
    if (!data || data.ok === false || !Array.isArray(data.sections)) {
        byId('timelineChart').innerHTML = '<p class="tl-note">' + escapeHtml(data?.message || 'Could not load the timeline. Check the app and database.') + '</p>';
        byId('timelineQueue').innerHTML = '';
        byId('timelineConflicts').innerHTML = '';
        return;
    }
    timelineData = data;
    renderTimeline();
}

// Put overlapping bars of one section on separate rows so none hide each other.
function tlAssignRows(items) {
    const rowEnds = [];
    items.sort((a, b) => a.s - b.s || a.e - b.e);
    items.forEach(item => {
        let row = rowEnds.findIndex(end => end <= item.s);
        if (row === -1) { row = rowEnds.length; rowEnds.push(0); }
        rowEnds[row] = item.e;
        item.row = row;
    });
    return Math.max(1, rowEnds.length);
}

function tlOverlaps(items) {
    const bands = [];
    for (let i = 0; i < items.length; i++) {
        for (let j = i + 1; j < items.length; j++) {
            const a = items[i], b = items[j];
            if (a.kind !== 'req' && b.kind !== 'req') continue; // only waiting requests cause new conflicts
            const s = Math.max(a.s, b.s), e = Math.min(a.e, b.e);
            if (s < e) bands.push({ s, e });
        }
    }
    return bands;
}

function renderTimeline() {
    const data = timelineData;
    if (!data) return;
    const sections = data.sections.map(sec => sec.section_id || sec.sectionId);
    const names = {};
    data.sections.forEach(sec => { names[sec.section_id || sec.sectionId] = sec.section_name || sec.sectionName || ''; });

    const perSection = {};
    sections.forEach(id => { perSection[id] = []; });
    const push = (sectionId, item) => {
        if (item.s === null || item.e === null || item.e <= item.s || !perSection[sectionId]) return;
        perSection[sectionId].push(item);
    };

    (data.trains || []).forEach(t => push(t.sectionId, {
        kind: 'train', label: t.label, s: tlMinutes(t.startTime), e: tlMinutes(t.endTime),
        tip: t.label + ' ' + t.startTime + '–' + t.endTime }));
    (data.blocks || []).forEach(b => push(b.section_id, {
        kind: 'block', label: b.task_id + ' ' + (b.dept_name || ''), s: tlMinutes(b.start_time), e: tlMinutes(b.end_time),
        tip: b.task_id + ' approved · ' + (b.work_desc || '') + ' · ' + b.start_time + '–' + b.end_time + ' · Team: ' + (b.assigned_workers || 'Not recorded') }));
    (data.requests || []).forEach(r => push(r.sectionId, {
        kind: 'req', label: r.taskId + ' ' + r.department + ' (' + r.priority + ')', s: tlMinutes(r.startTime), e: tlMinutes(r.endTime),
        tip: r.taskId + ' ' + requestStatusLabel(r.status) + ' · ' + (r.workDesc || '') + ' · ' + r.startTime + '–' + r.endTime + ' · Team: ' + (r.requestedTeam || 'ANY') }));
    let html = '<div class="tl-chart"><div class="tl-axis">';
    for (let h = 0; h <= 24; h += 2) {
        html += '<span class="tl-tick" style="left:' + tlPercent(h * 60) + '%">' + String(h % 24).padStart(2, '0') + ':00</span>';
    }
    html += '</div>';

    sections.forEach(id => {
        const items = perSection[id];
        const rows = tlAssignRows(items);
        const height = rows * 30 + 12;
        html += '<div class="tl-row" style="height:' + height + 'px"><div class="tl-label" title="' + escapeHtml(names[id]) + '">' + escapeHtml(id) + '</div>'
              + '<div class="tl-lane" style="height:' + height + 'px">';
        tlOverlaps(items).forEach((band, index) => {
            html += '<div class="tl-conflict-band" style="left:' + tlPercent(band.s) + '%;width:' + (tlPercent(band.e) - tlPercent(band.s)) + '%">'
                  + (index === 0 ? '<span>OVERLAP</span>' : '') + '</div>';
        });
        items.forEach(item => {
            html += '<div class="tl-bar ' + item.kind + '" style="left:' + tlPercent(item.s) + '%;width:' + (tlPercent(item.e) - tlPercent(item.s))
                  + '%;top:' + (6 + item.row * 30) + 'px" title="' + escapeHtml(item.tip) + '">' + escapeHtml(item.label) + '</div>';
        });
        html += '</div></div>';
    });
    html += '</div>';
    byId('timelineChart').innerHTML = html;

    renderTimelineQueue();
    renderTimelineConflicts();

    // Open the view around the working day instead of midnight.
    const scroller = byId('timelineScroll');
    if (scroller && !scroller.dataset.positioned) {
        scroller.scrollLeft = scroller.scrollWidth * (6 / 24);
        scroller.dataset.positioned = '1';
    }
}

function renderTimelineQueue() {
    const pending = (timelineData.requests || []).filter(r => String(r.status).toUpperCase() === 'PENDING');
    pending.sort((a, b) => a.effectiveRank - b.effectiveRank || b.ageMin - a.ageMin);
    if (!pending.length) {
        byId('timelineQueue').innerHTML = '<p class="tl-note" style="margin-top:10px">No requests are waiting for this date.</p>';
        return;
    }
    byId('timelineQueue').innerHTML = pending.map((r, index) => {
        const rank = Math.min(4, Math.max(1, Number(r.effectiveRank) || 4));
        return '<div class="tl-queue-item r' + rank + '"><b>' + (index + 1) + '. ' + escapeHtml(r.taskId) + '</b> [' + escapeHtml(r.priority) + ']'
            + (isTrueFlag(r.aged) ? ' <span class="tl-aging">↑ Aging</span>' : '')
            + '<small>Section: ' + escapeHtml(r.sectionId) + ' · ' + escapeHtml(r.department) + '</small>'
            + '<small>Waiting: ' + Number(r.ageMin) + ' min · Rank ' + rank + '</small></div>';
    }).join('');
}

function renderTimelineConflicts() {
    const conflicts = timelineData.conflicts || [];
    const requests = timelineData.requests || [];
    const info = id => requests.find(r => r.taskId === id);
    if (!conflicts.length) {
        byId('timelineConflicts').innerHTML = '<div class="tl-ok">No overlapping requests on this date.</div>';
        return;
    }
    byId('timelineConflicts').innerHTML = conflicts.map(c => {
        const a = info(c.taskA), b = info(c.taskB);
        const nameA = escapeHtml(c.taskA) + (a ? ' (' + escapeHtml(a.department) + ')' : '');
        const nameB = escapeHtml(c.taskB) + (b ? ' (' + escapeHtml(b.department) + ')' : '');
        const rejectA = a && String(a.status).toUpperCase() === 'PENDING'
            ? '<button class="tl-btn tl-red" type="button" onclick="rejectFromTimeline(\'' + escapeHtml(c.taskA) + '\')">Reject ' + escapeHtml(c.taskA) + '</button>' : '';
        const rejectB = b && String(b.status).toUpperCase() === 'PENDING'
            ? '<button class="tl-btn tl-red" type="button" onclick="rejectFromTimeline(\'' + escapeHtml(c.taskB) + '\')">Reject ' + escapeHtml(c.taskB) + '</button>' : '';
        return '<div class="tl-conflict-row"><span><strong>TIME OVERLAP:</strong> ' + nameA + ' conflicts with ' + nameB
            + ' on section ' + escapeHtml(c.sectionId) + '</span><span>' + rejectA + ' ' + rejectB + '</span></div>';
    }).join('');
}

async function rejectFromTimeline(taskId) {
    const reason = await askForText('Why should ' + taskId + ' be rejected? (5–500 characters)');
    if (reason === null) return;
    if (reason.trim().length < 5 || reason.trim().length > 500) {
        tlSetMessage('Enter a reason of 5–500 characters.', true);
        return;
    }
    const result = await api('/api/control/requests/' + encodeURIComponent(taskId) + '/decision', 'POST', { decision: 'REJECTED', reason });
    await loadTimeline();
    tlSetMessage(result.message || (result.ok ? 'Decision saved.' : 'Decision not saved.'), !result.ok);
}
