(() => {
  const form = document.querySelector('#planning-form');
  const stages = [...document.querySelectorAll('[data-stage]')];
  const steps = [...document.querySelectorAll('[data-step]')];
  const modeButtons = [...document.querySelectorAll('[data-mode]')];
  const destination = document.querySelector('#destination');
  const title = document.querySelector('#trip-title');
  const date = document.querySelector('#start-date');
  const days = document.querySelector('#total-days');
  const style = document.querySelector('#trip-style');
  const group = document.querySelector('#travel-group');
  const requirements = document.querySelector('#requirements');
  const back = document.querySelector('#back-button');
  const result = document.querySelector('#preview-result');
  let step = 1;
  let reached = 1;
  let mode = 'manual';

  function updateReview() {
    document.querySelector('#review-destination').textContent = destination.value;
    document.querySelector('#review-title').textContent = title.value || `${destination.value}的旅行`;
    document.querySelector('#review-date').textContent = date.value || '日期待定';
    document.querySelector('#review-pace').textContent = `${days.value} 天 · ${style.value} · ${group.value}`;
  }
  function showStep(next) {
    step = next;
    reached = Math.max(reached, step);
    for (const stage of stages) {
      stage.hidden = Number(stage.dataset.stage) !== step;
      stage.disabled = stage.hidden;
    }
    for (const button of steps) {
      const value = Number(button.dataset.step);
      button.disabled = value > reached;
      button.classList.toggle('complete', value < step);
      if (value === step) button.setAttribute('aria-current', 'step');
      else button.removeAttribute('aria-current');
    }
    back.hidden = step === 1;
    result.hidden = true;
    document.querySelector('#continue-label').textContent = step < 3 ? '继续' : '预览确认';
    updateReview();
    const heading = stages[step - 1].querySelector('h2');
    heading.focus({ preventScroll: true });
    heading.scrollIntoView({ block: 'nearest', behavior: 'instant' });
  }
  function validateStage(index) {
    const stage = stages[index - 1];
    const wasDisabled = stage.disabled;
    stage.disabled = false;
    const invalid = [...stage.querySelectorAll('input,select,textarea')].find(input => !input.checkValidity());
    stage.disabled = wasDisabled;
    if (!invalid) return true;
    if (step !== index) showStep(index);
    invalid.reportValidity();
    return false;
  }
  form.addEventListener('submit', event => {
    event.preventDefault();
    if (!validateStage(step)) return;
    if (step < 3) {
      showStep(step + 1);
      return;
    }
    if (!validateStage(1) || !validateStage(2)) return;
    updateReview();
    result.textContent = mode === 'manual'
      ? '旅行构想已确认到预览步骤。此处不会创建或保存路书；准备出发时，可从页底进入正式规划页。'
      : '已预览 AI 创建所需的输入。此处不会调用 AI 或生成草稿；实际能力以正式规划页为准。';
    result.hidden = false;
    result.scrollIntoView({ block: 'nearest', behavior: 'instant' });
  });
  back.addEventListener('click', () => showStep(Math.max(1, step - 1)));
  for (const button of steps) button.addEventListener('click', () => showStep(Number(button.dataset.step)));
  for (const button of modeButtons) button.addEventListener('click', () => {
    mode = button.dataset.mode;
    for (const other of modeButtons) other.setAttribute('aria-pressed', String(other === button));
    document.querySelector('#mode-description').textContent = mode === 'manual'
      ? '从一个目的地开始，填写旅行偏好，把想法慢慢变成行程。'
      : '先整理目的地与旅行偏好；此预览只演示步骤，不调用 AI。';
    document.querySelector('#review-description').textContent = mode === 'manual'
      ? '正式创建私密路书后，可以逐天填写地点、时间和费用。'
      : '正式启用 AI 后，可根据这些输入生成草稿，再进入工作台完善。';
    requirements.maxLength = mode === 'manual' ? 500 : 1500;
    result.hidden = true;
  });
  form.addEventListener('input', () => { result.hidden = true; });

  const image = document.querySelector('.landscape img');
  function imageFallback() {
    document.documentElement.classList.add('image-failed');
    image.hidden = true;
  }
  image.addEventListener('error', imageFallback);
  if (image.complete && !image.naturalWidth) imageFallback();

  window.addEventListener('pageshow', event => {
    if (!event.persisted || matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    const invitation = document.querySelector('.planning-invitation');
    invitation.style.animation = 'none';
    void invitation.offsetWidth;
    invitation.style.animation = '';
  });
})();
