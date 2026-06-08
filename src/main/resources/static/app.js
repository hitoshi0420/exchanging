const fileInput = document.getElementById('fileInput');
const selectedFilesBox = document.getElementById('selectedFiles');
const convertForm = document.getElementById('convertForm');
const resultBox = document.getElementById('result');
const submitButton = document.getElementById('submitButton');
const fileHint = document.getElementById('fileHint');
const capabilitiesBox = document.getElementById('capabilities');
const refreshCapabilitiesButton = document.getElementById('refreshCapabilities');
const historyBox = document.getElementById('history');
const refreshHistoryButton = document.getElementById('refreshHistory');
const clearHistoryButton = document.getElementById('clearHistory');
const previewBox = document.getElementById('previewBox');

let formatMap = {};
let selectedItems = [];
let itemId = 1;

init();

async function init() {
  await Promise.all([loadFormats(), loadCapabilities(), loadHistory()]);
  bindEvents();
  renderSelectedFiles();
}

function bindEvents() {
  fileInput.addEventListener('change', handleFileInputChange);
  convertForm.addEventListener('submit', handleSubmit);
  refreshCapabilitiesButton.addEventListener('click', loadCapabilities);
  refreshHistoryButton.addEventListener('click', loadHistory);
  clearHistoryButton.addEventListener('click', clearHistory);
  selectedFilesBox.addEventListener('change', handleSelectedItemChange);
  selectedFilesBox.addEventListener('click', handleSelectedItemClick);
  resultBox.addEventListener('click', handleResultAction);
  historyBox.addEventListener('click', handleHistoryAction);
}

async function loadFormats() {
  try {
    const response = await fetch('/api/formats');
    if (!response.ok) throw new Error('格式加载失败');
    formatMap = await response.json();
  } catch {
    formatMap = {};
    renderError('无法加载支持的格式列表，请刷新页面重试。');
  }
}

async function loadCapabilities() {
  capabilitiesBox.innerHTML = '<div class="capability-item"><strong>检测中...</strong><span class="capability-state">请稍候</span></div>';
  try {
    const response = await fetch('/api/health');
    const data = await response.json();
    const capabilities = data.capabilities || {};
    const suggestionHtml = (capabilities.suggestions || [])
      .map((s) => `<div class="capability-hint">${escapeHtml(s)}</div>`)
      .join('');
    capabilitiesBox.innerHTML =
      renderCapability('LibreOffice', capabilities.soffice) +
      renderCapability('Pandoc', capabilities.pandoc) +
      suggestionHtml;
  } catch {
    capabilitiesBox.innerHTML = '<div class="capability-item"><strong>环境检测失败</strong><span class="capability-state missing">请检查服务是否正常</span></div>';
  }
}

async function loadHistory() {
  historyBox.className = 'history-list';
  historyBox.innerHTML = '正在加载历史文件...';
  try {
    const response = await fetch('/api/history');
    const data = await response.json();
    const items = data.items || [];
    if (items.length === 0) {
      historyBox.className = 'history-list empty';
      historyBox.innerHTML = '暂无历史文件。';
      return;
    }
    historyBox.className = 'history-list';
    historyBox.innerHTML = items
      .map((item) => {
        const filename = item.fileName || '';
        const previewDisabled = item.previewable ? '' : 'disabled';
        return `
          <div class="history-item">
            <div>
              <div class="history-name">${escapeHtml(filename)}</div>
              <div class="history-meta">.${escapeHtml(item.format || '')} · ${formatBytes(item.size || 0)} · ${formatTime(item.lastModified)}</div>
            </div>
            <div class="row-actions">
              <button class="mini-button js-history-preview" type="button" data-url="${escapeHtml(item.previewUrl || '')}" data-name="${escapeHtml(filename)}" ${previewDisabled}>预览</button>
              <button class="mini-button js-history-download" type="button" data-url="${escapeHtml(item.downloadUrl || '')}" data-name="${escapeHtml(filename)}">下载</button>
              <button class="mini-button danger js-history-delete" type="button" data-name="${escapeHtml(filename)}">删除</button>
            </div>
          </div>
        `;
      })
      .join('');
  } catch {
    historyBox.className = 'history-list error';
    historyBox.innerHTML = '历史记录加载失败。';
  }
}

function renderCapability(name, available) {
  return `
    <div class="capability-item">
      <strong>${name}</strong>
      <span class="capability-state ${available ? 'ok' : 'missing'}">${available ? '已检测到' : '未检测到'}</span>
    </div>
  `;
}

function handleFileInputChange() {
  const files = Array.from(fileInput.files || []);
  if (files.length === 0) {
    return;
  }
  addFiles(files);
  fileInput.value = '';
}

function addFiles(files) {
  for (const file of files) {
    const ext = getExtension(file.name);
    const outputs = formatMap[ext]?.outputs || [];
    selectedItems.push({
      id: String(itemId++),
      file,
      ext,
      outputs,
      supported: outputs.length > 0,
      targetFormat: outputs[0] || ''
    });
  }
  renderSelectedFiles();
}

function renderSelectedFiles() {
  if (selectedItems.length === 0) {
    selectedFilesBox.className = 'selected-files empty';
    selectedFilesBox.innerHTML = '还没有添加文件。';
    fileHint.textContent = '每次选择后会自动加入列表，支持为每个文件单独设置目标格式';
    return;
  }

  selectedFilesBox.className = 'selected-files';
  selectedFilesBox.innerHTML = selectedItems
    .map((item) => {
      const outputs = item.outputs
        .map((output) => `<option value="${output}" ${item.targetFormat === output ? 'selected' : ''}>${output.toUpperCase()}</option>`)
        .join('');
      return `
        <div class="selected-item">
          <div class="selected-meta">
            <div class="selected-name">${escapeHtml(item.file.name)}</div>
            <div class="selected-ext">源格式：.${escapeHtml(item.ext || '-')}</div>
          </div>
          <select class="item-target js-item-target" data-id="${item.id}" ${item.supported ? '' : 'disabled'}>
            ${item.supported ? outputs : '<option value="">不支持该格式</option>'}
          </select>
          <button class="mini-button danger js-item-remove" type="button" data-id="${item.id}">移除</button>
        </div>
      `;
    })
    .join('');

  fileHint.textContent = `已添加 ${selectedItems.length} 个文件`;
}

function handleSelectedItemChange(event) {
  const target = event.target;
  if (!target.classList.contains('js-item-target')) {
    return;
  }
  const id = target.dataset.id || '';
  const item = selectedItems.find((v) => v.id === id);
  if (!item) {
    return;
  }
  item.targetFormat = target.value;
}

function handleSelectedItemClick(event) {
  const button = event.target.closest('.js-item-remove');
  if (!button) {
    return;
  }
  const id = button.dataset.id || '';
  selectedItems = selectedItems.filter((item) => item.id !== id);
  renderSelectedFiles();
}

async function handleSubmit(event) {
  event.preventDefault();
  if (selectedItems.length === 0) {
    return renderError('请先添加文件。');
  }

  const invalid = selectedItems.find((item) => !item.supported || !item.targetFormat);
  if (invalid) {
    return renderError(`文件 ${invalid.file.name} 未设置有效目标格式。`);
  }

  const formData = new FormData();
  for (const item of selectedItems) {
    formData.append('files', item.file);
    formData.append('targetFormats', item.targetFormat);
  }

  submitButton.disabled = true;
  submitButton.textContent = '转换中...';
  resultBox.className = 'result';
  resultBox.innerHTML = `正在转换 ${selectedItems.length} 个文件，请稍候。`;

  try {
    const response = await fetch('/api/convert/batch', { method: 'POST', body: formData });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || '转换失败');
    renderBatchResult(data);
    await loadHistory();
  } catch (error) {
    renderError(error.message || '转换失败');
  } finally {
    submitButton.disabled = false;
    submitButton.textContent = '开始转换';
  }
}

function renderBatchResult(data) {
  const items = data.items || [];
  const lines = items
    .map((item) => {
      const name = escapeHtml(item.originalName || item.outputFileName || '未知文件');
      if (!item.success) {
        return `<div class="batch-line fail"><span>${name}</span><span>失败：${escapeHtml(item.message || '转换失败')}</span></div>`;
      }
      const previewDisabled = item.previewable ? '' : 'disabled';
      return `
        <div class="batch-line ok">
          <span>${name} -> <span class="code-inline">.${escapeHtml(item.targetFormat || '')}</span></span>
          <div class="row-actions">
            <button class="mini-button js-result-preview" type="button" data-url="${escapeHtml(item.previewUrl || '')}" data-name="${escapeHtml(item.outputFileName || '')}" ${previewDisabled}>预览</button>
            <button class="mini-button js-result-download" type="button" data-url="${escapeHtml(item.downloadUrl || '')}" data-name="${escapeHtml(item.outputFileName || '')}">下载</button>
          </div>
        </div>
      `;
    })
    .join('');

  resultBox.className = 'result success';
  resultBox.innerHTML = `
    <div class="result-grid">
      <div><strong>总文件数：</strong>${Number(data.total || items.length)}</div>
      <div><strong>成功：</strong>${Number(data.successCount || 0)}，<strong>失败：</strong>${Number(data.failedCount || 0)}</div>
      <div class="batch-result">${lines}</div>
    </div>
  `;

  const firstPreviewable = items.find((item) => item.success && item.previewable && item.previewUrl);
  if (firstPreviewable) {
    renderPreview(firstPreviewable.previewUrl, firstPreviewable.outputFileName || '预览文件');
  }
}

function handleResultAction(event) {
  const downloadButton = event.target.closest('.js-result-download');
  if (downloadButton) {
    downloadConvertedFile(downloadButton.dataset.url || '', downloadButton.dataset.name || '');
    return;
  }
  const previewButton = event.target.closest('.js-result-preview');
  if (previewButton) {
    renderPreview(previewButton.dataset.url || '', previewButton.dataset.name || '预览文件');
  }
}

function handleHistoryAction(event) {
  const downloadButton = event.target.closest('.js-history-download');
  if (downloadButton) {
    downloadConvertedFile(downloadButton.dataset.url || '', downloadButton.dataset.name || '');
    return;
  }
  const previewButton = event.target.closest('.js-history-preview');
  if (previewButton) {
    renderPreview(previewButton.dataset.url || '', previewButton.dataset.name || '预览文件');
    return;
  }
  const deleteButton = event.target.closest('.js-history-delete');
  if (deleteButton) {
    deleteHistoryFile(deleteButton.dataset.name || '');
  }
}

function renderPreview(url, fileName) {
  if (!url) {
    previewBox.className = 'preview-box empty';
    previewBox.textContent = '该文件暂不支持预览。';
    return;
  }
  previewBox.className = 'preview-box';
  previewBox.innerHTML = `
    <div class="preview-title">${escapeHtml(fileName)}</div>
    <iframe class="preview-frame" src="${escapeHtml(url)}" title="转换结果预览"></iframe>
  `;
}

async function deleteHistoryFile(fileName) {
  if (!fileName) {
    return;
  }
  try {
    const response = await fetch(`/api/history/${encodeURIComponent(fileName)}`, { method: 'DELETE' });
    const data = await response.json();
    if (!response.ok || !data.success) {
      throw new Error(data.error || '删除失败');
    }
    await loadHistory();
  } catch (error) {
    renderError(error.message || '删除失败');
  }
}

async function clearHistory() {
  try {
    const response = await fetch('/api/history', { method: 'DELETE' });
    const data = await response.json();
    if (!response.ok || !data.success) {
      throw new Error(data.error || '清空失败');
    }
    await loadHistory();
  } catch (error) {
    renderError(error.message || '清空失败');
  }
}

async function downloadConvertedFile(url, filename) {
  if (!url) {
    return renderError('下载地址无效');
  }
  try {
    const response = await fetch(url);
    if (!response.ok) {
      throw new Error('下载失败');
    }
    const blob = await response.blob();
    const objectUrl = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = objectUrl;
    link.download = filename || 'downloaded-file';
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(objectUrl);
  } catch (error) {
    renderError(error.message || '下载失败');
  }
}

function renderError(message) {
  resultBox.className = 'result error';
  resultBox.textContent = `失败：${message}`;
}

function getExtension(filename) {
  const index = filename.lastIndexOf('.');
  return index >= 0 ? filename.slice(index + 1).toLowerCase() : '';
}

function formatBytes(value) {
  const size = Number(value || 0);
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / (1024 * 1024)).toFixed(1)} MB`;
}

function formatTime(value) {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '-';
  return date.toLocaleString('zh-CN', { hour12: false });
}

function escapeHtml(value) {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}
