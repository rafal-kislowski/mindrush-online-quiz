import { CommonModule } from '@angular/common';
import { Component } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';

@Component({
  selector: 'app-placeholder',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './placeholder.component.html',
  styleUrl: './placeholder.component.scss'
})
export class PlaceholderComponent {
  constructor(private readonly route: ActivatedRoute) {}

  get title(): string {
    return (this.route.snapshot.data?.['title'] as string) ?? 'Coming soon';
  }
}

