import { CommonModule } from '@angular/common';
import { Component, OnInit } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import {
  AdminQuestionDto,
  AdminQuizApi,
  AdminQuizDetailDto,
  AdminQuizDto,
  AdminQuizListItemDto,
} from '../../core/api/admin-quiz.api';

@Component({
  selector: 'app-admin-quiz',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './admin-quiz.component.html',
  styleUrl: './admin-quiz.component.scss',
})
export class AdminQuizComponent implements OnInit {
  tab: 'manage' | 'create' = 'manage';
  error: string | null = null;
  createdQuiz: AdminQuizDto | null = null;

  quizzes: AdminQuizListItemDto[] = [];
  selectedQuiz: AdminQuizDetailDto | null = null;
  selectedQuestionId: number | null = null;

  loadingList = false;
  loadingQuiz = false;
  savingQuiz = false;
  savingQuestion = false;
  deletingQuestion = false;
  deletingQuiz = false;
  creating = false;
  addingQuestion = false;

  readonly quizForm = new FormGroup({
    title: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(120)] }),
    description: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(500)] }),
    categoryName: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(64)] }),
  });

  readonly questionForm = new FormGroup({
    prompt: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(500)] }),
    correctIndex: new FormControl(0, { nonNullable: true }),
    o1: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(200)] }),
    o2: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(200)] }),
    o3: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(200)] }),
    o4: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(200)] }),
  });

  readonly editQuizForm = new FormGroup({
    title: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(120)] }),
    description: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(500)] }),
    categoryName: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(64)] }),
  });

  readonly editQuestionForm = new FormGroup({
    prompt: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(500)] }),
    correctIndex: new FormControl(0, { nonNullable: true }),
    o1: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(200)] }),
    o2: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(200)] }),
    o3: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(200)] }),
    o4: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(200)] }),
  });

  constructor(private readonly api: AdminQuizApi) {}

  ngOnInit(): void {
    this.loadList();
  }

  setTab(tab: 'manage' | 'create'): void {
    this.tab = tab;
    this.error = null;
    if (tab === 'manage') this.loadList();
  }

  loadList(): void {
    if (this.loadingList) return;
    this.loadingList = true;
    this.api.listQuizzes().subscribe({
      next: (list) => {
        this.loadingList = false;
        this.quizzes = list;
      },
      error: (err) => {
        this.loadingList = false;
        this.error = err?.error?.message ?? 'Failed to load quizzes';
      },
    });
  }

  selectQuiz(id: number): void {
    this.error = null;
    this.loadingQuiz = true;
    this.selectedQuestionId = null;
    this.api.getQuiz(id).subscribe({
      next: (quiz) => {
        this.loadingQuiz = false;
        this.selectedQuiz = quiz;
        this.editQuizForm.setValue({
          title: quiz.title ?? '',
          description: quiz.description ?? '',
          categoryName: quiz.categoryName ?? '',
        });
      },
      error: (err) => {
        this.loadingQuiz = false;
        this.error = err?.error?.message ?? 'Failed to load quiz';
      },
    });
  }

  saveQuiz(): void {
    if (!this.selectedQuiz) return;
    if (this.savingQuiz) return;
    if (this.editQuizForm.invalid) return;
    this.error = null;

    const title = this.editQuizForm.controls.title.value.trim();
    const description = this.editQuizForm.controls.description.value.trim();
    const categoryName = this.editQuizForm.controls.categoryName.value.trim();

    this.savingQuiz = true;
    this.api
      .updateQuiz(this.selectedQuiz.id, {
        title,
        description: description || null,
        categoryName: categoryName || null,
      })
      .subscribe({
        next: (updated) => {
          this.savingQuiz = false;
          this.selectedQuiz = {
            ...this.selectedQuiz!,
            title: updated.title,
            description: updated.description,
            categoryName: updated.categoryName,
          };
          this.loadList();
        },
        error: (err) => {
          this.savingQuiz = false;
          this.error = err?.error?.message ?? 'Failed to save quiz';
        },
      });
  }

  selectQuestion(q: AdminQuestionDto): void {
    this.selectedQuestionId = q.id;
    const correctIndex = Math.max(
      0,
      q.options.findIndex((o) => o.correct)
    );
    const texts = q.options
      .slice()
      .sort((a, b) => a.orderIndex - b.orderIndex)
      .map((o) => o.text);

    this.editQuestionForm.setValue({
      prompt: q.prompt,
      correctIndex: correctIndex < 0 ? 0 : correctIndex,
      o1: texts[0] ?? '',
      o2: texts[1] ?? '',
      o3: texts[2] ?? '',
      o4: texts[3] ?? '',
    });
  }

  saveQuestion(): void {
    if (!this.selectedQuiz) return;
    if (!this.selectedQuestionId) return;
    if (this.savingQuestion) return;
    if (this.editQuestionForm.invalid) return;
    this.error = null;

    const quiz = this.selectedQuiz;
    const q = quiz.questions.find((x) => x.id === this.selectedQuestionId);
    if (!q) return;

    const prompt = this.editQuestionForm.controls.prompt.value.trim();
    const correctIndex = this.editQuestionForm.controls.correctIndex.value;
    const texts = [
      this.editQuestionForm.controls.o1.value.trim(),
      this.editQuestionForm.controls.o2.value.trim(),
      this.editQuestionForm.controls.o3.value.trim(),
      this.editQuestionForm.controls.o4.value.trim(),
    ];

    const optionsSorted = q.options.slice().sort((a, b) => a.orderIndex - b.orderIndex);
    const payloadOptions = optionsSorted.map((o, idx) => ({
      id: o.id,
      text: texts[idx],
      correct: idx === correctIndex,
    }));

    this.savingQuestion = true;
    this.api
      .updateQuestion(quiz.id, q.id, {
        prompt,
        options: payloadOptions,
      })
      .subscribe({
        next: () => {
          this.savingQuestion = false;
          this.selectQuiz(quiz.id);
        },
        error: (err) => {
          this.savingQuestion = false;
          this.error = err?.error?.message ?? 'Failed to save question';
        },
      });
  }

  deleteSelectedQuestion(): void {
    if (!this.selectedQuiz) return;
    if (!this.selectedQuestionId) return;
    if (this.deletingQuestion) return;
    this.error = null;

    const quizId = this.selectedQuiz.id;
    const questionId = this.selectedQuestionId;

    this.deletingQuestion = true;
    this.api.deleteQuestion(quizId, questionId).subscribe({
      next: () => {
        this.deletingQuestion = false;
        this.selectedQuestionId = null;
        this.selectQuiz(quizId);
        this.loadList();
      },
      error: (err) => {
        this.deletingQuestion = false;
        this.error = err?.error?.message ?? 'Failed to delete question';
      },
    });
  }

  deleteSelectedQuiz(): void {
    if (!this.selectedQuiz) return;
    if (this.deletingQuiz) return;
    this.error = null;

    const quizId = this.selectedQuiz.id;
    this.deletingQuiz = true;
    this.api.deleteQuiz(quizId).subscribe({
      next: () => {
        this.deletingQuiz = false;
        this.selectedQuiz = null;
        this.selectedQuestionId = null;
        this.loadList();
      },
      error: (err) => {
        this.deletingQuiz = false;
        this.error = err?.error?.message ?? 'Failed to delete quiz';
      },
    });
  }

  createQuiz(): void {
    this.error = null;
    if (this.quizForm.invalid) return;

    const title = this.quizForm.controls.title.value.trim();
    const description = this.quizForm.controls.description.value.trim();
    const categoryName = this.quizForm.controls.categoryName.value.trim();

    this.creating = true;
    this.api
      .createQuiz({
        title,
        description: description || null,
        categoryName: categoryName || null,
      })
      .subscribe({
        next: (quiz) => {
          this.creating = false;
          this.createdQuiz = quiz;
          this.tab = 'manage';
          this.loadList();
          this.selectQuiz(quiz.id);
        },
        error: (err) => {
          this.creating = false;
          this.error = err?.error?.message ?? 'Failed to create quiz';
        },
      });
  }

  addQuestion(): void {
    this.error = null;
    if (!this.createdQuiz) return;
    if (this.questionForm.invalid) return;

    const prompt = this.questionForm.controls.prompt.value.trim();
    const correctIndex = this.questionForm.controls.correctIndex.value;
    const texts = [
      this.questionForm.controls.o1.value.trim(),
      this.questionForm.controls.o2.value.trim(),
      this.questionForm.controls.o3.value.trim(),
      this.questionForm.controls.o4.value.trim(),
    ];

    this.addingQuestion = true;
    this.api
      .addQuestion(this.createdQuiz.id, {
        prompt,
        options: texts.map((text, idx) => ({ text, correct: idx === correctIndex })),
      })
      .subscribe({
        next: () => {
          this.addingQuestion = false;
          this.questionForm.reset({
            prompt: '',
            correctIndex: 0,
            o1: '',
            o2: '',
            o3: '',
            o4: '',
          });
        },
        error: (err) => {
          this.addingQuestion = false;
          this.error = err?.error?.message ?? 'Failed to add question';
        },
      });
  }
}
