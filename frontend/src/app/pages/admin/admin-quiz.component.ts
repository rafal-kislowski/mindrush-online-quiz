import { CommonModule } from '@angular/common';
import { Component } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { AdminQuizApi, AdminQuizDto } from '../../core/api/admin-quiz.api';

@Component({
  selector: 'app-admin-quiz',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './admin-quiz.component.html',
  styleUrl: './admin-quiz.component.scss',
})
export class AdminQuizComponent {
  error: string | null = null;
  createdQuiz: AdminQuizDto | null = null;

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

  constructor(private readonly api: AdminQuizApi) {}

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

