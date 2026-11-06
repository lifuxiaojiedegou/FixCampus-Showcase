(() => {
    'use strict';

    const updateCounts = [];
    document.querySelectorAll('[data-character-count-for]').forEach((counter) => {
        const field = document.getElementById(counter.dataset.characterCountFor);
        if (!field) return;
        const update = () => {
            counter.textContent = `已输入 ${Array.from(field.value).length} 个字符`;
        };
        counter.hidden = false;
        field.addEventListener('input', update);
        field.addEventListener('change', update);
        updateCounts.push(update);
        update();
    });

    const passwordControls = [];
    document.querySelectorAll('[data-password-toggle]').forEach((button) => {
        const input = document.getElementById(button.getAttribute('aria-controls'));
        if (!input) return;
        const conceal = () => {
            input.type = 'password';
            button.textContent = '显示';
            button.setAttribute('aria-label', button.dataset.showLabel);
        };
        button.addEventListener('click', () => {
            if (input.type === 'text') {
                conceal();
            } else {
                input.type = 'text';
                button.textContent = '隐藏';
                button.setAttribute('aria-label', button.dataset.hideLabel);
            }
        });
        button.hidden = false;
        input.parentElement.classList.add('has-toggle');
        passwordControls.push(conceal);
    });

    const resetForms = [];
    let activeSubmission = null;
    document.querySelectorAll('[data-auth-form]').forEach((form) => {
        const fields = form.querySelectorAll('input, select, textarea');
        fields.forEach((field) => {
            const clearMessage = () => field.setCustomValidity('');
            field.addEventListener('invalid', () => {
                clearMessage();
                if (field.validity.valueMissing) {
                    field.setCustomValidity(field.dataset.requiredMessage || '请填写此项');
                } else if (!field.validity.valid) {
                    field.setCustomValidity('请检查此项的填写内容');
                }
            });
            field.addEventListener('input', clearMessage);
            field.addEventListener('change', clearMessage);
        });
        // Enable native validation only after Chinese prompts are ready.
        // Without JavaScript, novalidate delegates to the server's Chinese errors.
        form.noValidate = false;
        const submit = form.querySelector('button[type="submit"]');
        const status = form.querySelector('[data-submit-status]');
        if (!submit || !status) return;
        const idleLabel = submit.textContent;
        const idleDisabled = submit.disabled;
        let submitting = false;
        let attemptId = 0;
        const recovery = document.createElement('div');
        recovery.className = 'submission-recovery';
        recovery.hidden = true;
        const recoveryHint = document.createElement('p');
        recoveryHint.className = 'field-help';
        const recoveryButton = document.createElement('button');
        recoveryButton.type = 'button';
        recoveryButton.className = 'button button-secondary';
        recoveryButton.textContent = '恢复操作';
        recovery.append(recoveryHint, recoveryButton);
        form.append(recovery);
        const pendingHint = '若提交已中断，可恢复操作，填写内容会保留。';
        const reset = () => {
            fields.forEach((field) => field.setCustomValidity(''));
            submitting = false;
            attemptId++;
            submit.disabled = idleDisabled;
            submit.removeAttribute('aria-busy');
            submit.textContent = idleLabel;
            status.textContent = '';
            recovery.hidden = true;
            recoveryButton.hidden = false;
            recoveryHint.textContent = pendingHint;
            delete form.dataset.submissionState;
            if (activeSubmission?.form === form) activeSubmission = null;
        };
        const recover = () => {
            if (!submitting) return;
            reset();
            recoveryHint.textContent = '已恢复操作，填写内容已保留。请核对上次操作结果后再提交。';
            recoveryButton.hidden = true;
            recovery.hidden = false;
            form.dataset.submissionState = 'interrupted';
            status.textContent = recoveryHint.textContent;
            passwordControls.forEach((conceal) => conceal());
        };
        recoveryButton.addEventListener('click', () => {
            // Stop waiting for native navigation; never retry a POST automatically.
            window.stop();
            recover();
            submit.focus();
        });
        resetForms.push(reset);
        // Native validation runs before submit; keep ordinary browser POST navigation.
        form.addEventListener('submit', (event) => {
            if (submitting) {
                event.preventDefault();
                return;
            }
            submitting = true;
            submit.disabled = true;
            submit.setAttribute('aria-busy', 'true');
            submit.textContent = form.dataset.pendingLabel;
            status.textContent = form.dataset.pendingLabel;
            recoveryHint.textContent = pendingHint;
            recoveryButton.hidden = false;
            recovery.hidden = false;
            form.dataset.submissionState = 'pending';
            const currentAttempt = ++attemptId;
            activeSubmission = { form, recover: () => {
                if (currentAttempt === attemptId) recover();
            } };
        });
    });

    document.querySelectorAll('[data-error-target]').forEach((link) => {
        link.addEventListener('click', (event) => {
            const input = document.getElementById(link.dataset.errorTarget);
            if (!input) return;
            event.preventDefault();
            input.focus();
        });
    });
    document.querySelector('input[aria-invalid="true"], select[aria-invalid="true"], textarea[aria-invalid="true"]')?.focus();

    // Back/forward cache can restore a disabled submit button and a revealed password.
    window.addEventListener('pageshow', () => {
        resetForms.forEach((reset) => reset());
        passwordControls.forEach((conceal) => conceal());
        updateCounts.forEach((update) => update());
    });
    window.addEventListener('pagehide', () => passwordControls.forEach((conceal) => conceal()));

    // Native navigation cancellation does not fire pageshow. Keep a manual fallback above.
    window.navigation?.addEventListener('navigate', (event) => {
        const submission = activeSubmission;
        if (event.formData && submission) {
            // A later form may submit before this navigation aborts; recover only this attempt.
            event.signal.addEventListener('abort', submission.recover, { once: true });
        }
    });
})();
